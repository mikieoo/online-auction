# 시스템 구성

## 서비스 토폴로지

```
클라이언트 → Gateway(8080) ──REST──→ auction-service(8081) ──REST/gRPC──→ bid-service(8082)
                                       │      ▲                ←──REST──┘ (경매 조회)
                                       │      │
                                       │←──── Kafka ──── payment-service(8083)
                                       │                        ▲
                                       └──── REST(D3 임시) / Kafka(D9~) ─┘
```

- **Gateway** → 모든 서비스: HTTP 라우팅, JWT 검증 후 X-User-Id 헤더 전달. `/api/**`만 라우팅하고 `/internal/**`는 차단한다.
- **bid-service** → **auction-service**: 동기 REST(OpenFeign). 입찰 검증에 필요한 경매 상태·판매자·시작가·마감시간 조회.
- **auction-service** → **bid-service**: 동기 REST(OpenFeign, D5부터 gRPC). 낙찰 확정(현재 ACTIVE 입찰을 WINNER로).
- **auction-service** → **payment-service**: 현재 동기 REST(OpenFeign)로 결제 요청. D9부터 Kafka `AuctionWon`, `WinnerReassigned` 이벤트로 대체.
- **auction-service** → **bid-service**: Kafka 비동기(D8~). AuctionStarted, AuctionClosed 이벤트.
- **payment-service** → **auction-service**: Kafka 비동기(D9~). PaymentCompleted, PaymentFailed 이벤트.

서비스 간 주소는 각 서비스 설정의 고정 URL(`clients.*.url`, 기본 localhost 포트). D4에서 Eureka 서비스 이름으로 전환.

## 데이터 소유권

| 서비스 | DB | 소유 테이블 |
|--------|-----|-----------|
| auction-service | auction_db | product, auction, outbox, shedlock(스케줄러 락) |
| bid-service | bid_db | bid |
| payment-service | payment_db | payment |
| gateway | 없음 | — |

각 서비스는 자신의 DB만 접근한다. 다른 서비스의 데이터가 필요하면 동기 API 호출 또는 이벤트를 통해 얻는다. 서비스 간 호출의 DTO는 호출하는 쪽이 자체 정의한다.

## 대표 흐름: 입찰

1. 클라이언트가 bid-service에 `POST /api/v1/bids` (X-User-Id, auctionId, amount)
2. bid-service가 auction-service `GET /api/v1/auctions/{id}`로 판매자·상태·시작가·마감시간을 조회
3. bid-service가 규칙 검사(본인 경매, ACTIVE, 마감시간, 금액) 후 기존 ACTIVE 입찰을 OUTBID로, 새 입찰을 ACTIVE로 저장
4. 201 응답. auction-service에는 아무것도 저장되지 않는다(현재 최고가는 bid-service가 소유).

## 대표 흐름: 경매 마감 → 결제

목표 아키텍처(D9 이후):

1. 스케줄러(auction-service)가 end_time 도달한 경매를 CLOSED로 변경
2. auction-service가 bid-service에 최고 입찰자를 동기 조회
3. 입찰 0건이면 FAILED(유찰). 입찰 있으면 최고 입찰자를 낙찰자로 설정
4. AuctionWon 이벤트를 Kafka로 발행 (idempotencyKey 포함)
5. payment-service가 AuctionWon을 소비하여 Payment 생성 → 결제 시뮬레이션
6. 성공 시 PaymentCompleted, 실패 시 PaymentFailed 발행
7. auction-service가 결제 결과를 소비하여 COMPLETED 또는 차순위 승계(최대 3회)

현재 구현(D3, 동기):

1. 스케줄러(auction-service, ShedLock, 5초 주기) **A단계**: ACTIVE이고 end_time이 지난 경매를 각각 CLOSED로 저장. 외부 호출 없음.
2. **B단계**: CLOSED이고 낙찰자 없는 경매마다 bid-service `POST /internal/v1/bids/auctions/{id}/winner`로 낙찰 확정(ACTIVE → WINNER, 멱등).
3. 입찰 없음(`hasBids=false`) → FAILED. 낙찰 있음 → payment-service `POST /internal/v1/payments`로 결제 요청(idempotencyKey = `{auctionId}-{winnerId}-{reassignmentCount}`).
4. 결제 COMPLETED → 낙찰자 기록 + COMPLETED. FAILED → 낙찰자만 기록, CLOSED 유지(차순위 승계는 D10). 외부 호출 실패 → 저장 없이 다음 주기 재시도.

목표 흐름과의 차이: 낙찰자 저장이 결제 결과 뒤로 밀려 있고(3단계 ↔ 4단계 순서), auction→payment가 이벤트가 아니라 동기 호출이다. 두 가지 모두 D9에서 되돌린다.

## Kafka 파티션 키

모든 경매 관련 이벤트의 파티션 키는 auctionId. 같은 경매의 이벤트 순서를 보장한다.
