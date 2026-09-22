# 시스템 구성

## 서비스 토폴로지

```
클라이언트 ─(JWT)→ Gateway(8080) ─lb://─→ auction-service(8081) ⇄ bid-service(8082)
                       │                        │
                       │                        └─REST(D3 임시) / Kafka(D9~)→ payment-service(8083)
                       │
        discovery-service(Eureka, 8761) ← gateway·auction·bid·payment 전부 등록
```

- **Gateway** → 모든 서비스: 단일 공개 진입점. JWT 검증 후 `X-User-Id` 주입(클라이언트가 보낸 값은 제거). `/api/**`만 라우팅하고 `/internal/**`는 라우트가 없다. 대상은 `lb://{서비스 이름}` — Eureka에서 인스턴스를 찾아 부하 분산. 라우트마다 Circuit Breaker + fallback(503).
- **discovery-service**: Eureka 서버(로컬 전용). 서비스 이름 → 인스턴스 주소. GKE(D18)에서는 K8s Service DNS가 대신한다.
- **bid-service** → **auction-service**: 동기 REST(OpenFeign, 서비스 이름으로 조회, Circuit Breaker). 입찰 검증에 필요한 경매 상태·판매자·시작가·마감시간 조회. 호출 불가 시 입찰을 빠르게 503으로 거절한다.
- **auction-service** → **bid-service**: 낙찰 확정(현재 ACTIVE 입찰을 WINNER로). D5에서 gRPC로 전환(BidGrpcClient, blocking stub + Resilience4j CB). 서비스 디스커버리 `discovery:///bid-service`. REST 엔드포인트는 디버깅용으로 유지.
- **auction-service** → **payment-service**: 현재 동기 REST(OpenFeign + Circuit Breaker)로 결제 요청. D9부터 Kafka `AuctionWon`, `WinnerReassigned` 이벤트로 대체.
- **auction-service** → **bid-service**: Kafka 비동기(D8~). AuctionStarted, AuctionClosed 이벤트.
- **payment-service** → **auction-service**: Kafka 비동기(D9~). PaymentCompleted, PaymentFailed 이벤트.

서비스 간 주소는 Eureka로 찾는다. 설정 `clients.{서비스}.url`을 주면 그 고정 주소를 쓴다(discovery 없이 단독 실행할 때의 오버라이드).

## Circuit Breaker 배치

| 위치 | 브레이커 이름 | 실패로 세는 것 | 열렸을 때 |
|---|---|---|---|
| Gateway 라우트 | `auction-service`, `bid-service`, `payment-service` | 연결 실패, 타임아웃(8초). 하류가 돌려준 HTTP 응답(4xx·5xx)은 세지 않고 그대로 전달 | 즉시 503 `SERVICE_UNAVAILABLE` |
| bid-service → auction-service | `auction-service` | 연결 실패, 타임아웃, 상류 5xx. 업무 4xx(404·기타)는 세지 않음 | 입찰을 즉시 503 `UPSTREAM_UNAVAILABLE`로 거절 |
| auction-service → bid·payment | `bid-service`, `payment-service` | 연결 실패, 타임아웃, 5xx. 4xx는 세지 않음 | 정산 스케줄러가 그 주기의 남은 정산을 중단, 다음 주기에 재시도 |

두 층은 보호 대상이 다르다: Gateway 브레이커는 클라이언트의 대기 시간을, 서비스 간 브레이커는 서비스 내부 자원(스레드·커넥션·스케줄러 주기 시간)을 지킨다. 안쪽 서비스가 "상류가 죽어서 503"이라고 응답한 것은 Gateway 입장에서 정상 응답이므로 바깥 브레이커를 열지 않는다.

## 데이터 소유권

| 서비스 | DB | 소유 테이블 |
|--------|-----|-----------|
| auction-service | auction_db | product, auction, outbox, shedlock(스케줄러 락) |
| bid-service | bid_db | bid |
| payment-service | payment_db | payment |
| gateway, discovery-service | 없음 | — |

각 서비스는 자신의 DB만 접근한다. 다른 서비스의 데이터가 필요하면 동기 API 호출 또는 이벤트를 통해 얻는다. 서비스 간 호출의 DTO는 호출하는 쪽이 자체 정의한다.

## 대표 흐름: 입찰

1. 클라이언트가 Gateway에 `POST /api/v1/bids` (`Authorization: Bearer <JWT>`, auctionId, amount)
2. Gateway가 토큰을 검증하고 `X-User-Id`를 붙여 `lb://bid-service`로 전달
3. bid-service가 auction-service `GET /api/v1/auctions/{id}`로 판매자·상태·시작가·마감시간을 조회(Eureka로 주소 조회, Circuit Breaker 경유)
4. bid-service가 규칙 검사(본인 경매, ACTIVE, 마감시간, 금액) 후 기존 ACTIVE 입찰을 OUTBID로, 새 입찰을 ACTIVE로 저장
5. 201 응답. auction-service에는 아무것도 저장되지 않는다(현재 최고가는 bid-service가 소유).

## 대표 흐름: 경매 마감 → 결제

목표 아키텍처(D9 이후):

1. 스케줄러(auction-service)가 end_time 도달한 경매를 CLOSED로 변경
2. auction-service가 bid-service에 최고 입찰자를 동기 조회
3. 입찰 0건이면 FAILED(유찰). 입찰 있으면 최고 입찰자를 낙찰자로 설정
4. AuctionWon 이벤트를 Kafka로 발행 (idempotencyKey 포함)
5. payment-service가 AuctionWon을 소비하여 Payment 생성 → 결제 시뮬레이션
6. 성공 시 PaymentCompleted, 실패 시 PaymentFailed 발행
7. auction-service가 결제 결과를 소비하여 COMPLETED 또는 차순위 승계(최대 3회)

현재 구현(동기):

1. 스케줄러(auction-service, ShedLock, 5초 주기) **A단계**: ACTIVE이고 end_time이 지난 경매를 각각 CLOSED로 저장. 외부 호출 없음.
2. **B단계**: CLOSED이고 낙찰자 없는 경매마다 bid-service `POST /internal/v1/bids/auctions/{id}/winner`로 낙찰 확정(ACTIVE → WINNER, 멱등).
3. 입찰 없음(`hasBids=false`) → FAILED. 낙찰 있음 → payment-service `POST /internal/v1/payments`로 결제 요청(idempotencyKey = `{auctionId}-{winnerId}-{reassignmentCount}`).
4. 결제 COMPLETED → 낙찰자 기록 + COMPLETED. FAILED → 낙찰자만 기록, CLOSED 유지(차순위 승계는 D10). 외부 호출 실패 → 저장 없이 다음 주기 재시도. 브레이커가 열려 있으면 그 주기의 남은 정산을 중단한다.

목표 흐름과의 차이: 낙찰자 저장이 결제 결과 뒤로 밀려 있고(3단계 ↔ 4단계 순서), auction→payment가 이벤트가 아니라 동기 호출이다. 두 가지 모두 D9에서 되돌린다.

## Kafka 파티션 키

모든 경매 관련 이벤트의 파티션 키는 auctionId. 같은 경매의 이벤트 순서를 보장한다.
