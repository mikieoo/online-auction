# 시스템 구성

D21 기준 현재 구현을 적는다. 각 구간을 왜 그렇게 골랐는지는 `docs/notes/D{n}-*.md`에 있다.

## 서비스 토폴로지

```
                          ┌──────────── discovery-service (Eureka, 8761) ────────────┐
                          │       gateway·auction·bid·payment가 등록·조회           │
                          ▼                                                          │
클라이언트 ─(JWT)→ Gateway(8080) ─lb://─┬→ auction-service(8081) ─gRPC(9082)→ bid-service(8082)
                                        ├→ bid-service ─REST(Feign)→ auction-service
                                        └→ payment-service(8083)

Kafka:  auction-service ──auction-events──→ bid-service(수신 로그만), payment-service
        payment-service ──payment-events──→ auction-service
```

| 구간 | 방식 | 용도 |
|---|---|---|
| 클라이언트 → Gateway | HTTP + JWT | 유일한 공개 진입점. 토큰 검증 후 `X-User-Id` 주입(클라이언트가 보낸 값은 제거). `/api/**`만 라우팅, `/internal/**`은 라우트 없음(404) |
| Gateway → 서비스 | `lb://{서비스 이름}` | Eureka로 인스턴스를 찾아 부하 분산. 라우트마다 Circuit Breaker + 503 fallback |
| bid → auction | REST(OpenFeign) + Circuit Breaker | 입찰 검증용 경매 조회(상태·판매자·시작가·마감시각). 트랜잭션·분산 락 밖에서 호출(D20) |
| auction → bid | gRPC(blocking stub) + Circuit Breaker | 낙찰 확정 `ConfirmWinner`, 차순위 승계 `ReassignWinner`(D5, D10). REST 내부 API도 디버깅용으로 남아 있다 |
| auction → payment | Kafka `auction-events` | `AuctionWonEvent`, `WinnerReassignedEvent` → 결제 시작(D9) |
| payment → auction | Kafka `payment-events` | `PaymentCompletedEvent`, `PaymentFailedEvent` → 완료 또는 차순위 승계 |
| auction → bid | Kafka `auction-events` | `AuctionStartedEvent`, `AuctionClosedEvent`. bid-service는 아직 받아서 로그만 남긴다 |

- 서비스 간 주소는 Eureka로 찾는다. `clients.{서비스}.url`을 주면 그 고정 주소를 쓴다(discovery 없이 단독 실행할 때).
- auction-service의 `PaymentClient`(Feign)는 D9에서 호출부가 사라졌고 코드만 남아 있다.

## 이벤트 발행·소비 규칙

- **발행은 Outbox로 한다(D11).** 상태 변경과 같은 트랜잭션에서 `outbox` 테이블에 INSERT하고, `OutboxPublisher`(ShedLock 단일 실행)가 폴링해 Kafka로 보낸다. 커밋과 발행 사이에 죽어도 이벤트가 사라지지 않는다. 대신 at-least-once라 중복이 생길 수 있다.
- **소비는 멱등하게 한다(D12).** auction-service는 `processed_event`(eventId PK)에 INSERT하는 것과 업무 처리를 한 트랜잭션으로 묶는다. payment-service는 결제의 `idempotency_key` UNIQUE가 같은 역할을 한다.
- **실패는 재시도 후 DLT로 보낸다(D13).** `DefaultErrorHandler`가 1초 간격으로 2회 재시도하고, 그래도 실패하면 `<토픽>.DLT`로 보낸다. 역직렬화 실패는 재시도 없이 바로 DLT. 같은 파티션 안에서 재시도하므로 순서가 유지된다.
- **파티션 키는 모두 auctionId.** 같은 경매의 이벤트는 순서대로 처리된다.

## Circuit Breaker 배치

| 위치 | 브레이커 이름 | 실패로 세는 것 | 열렸을 때 |
|---|---|---|---|
| Gateway 라우트 | `auction-service`, `bid-service`, `payment-service` | 연결 실패, 타임아웃(8초). 하류가 돌려준 HTTP 응답(4xx·5xx)은 세지 않고 그대로 전달 | 즉시 503 `SERVICE_UNAVAILABLE` |
| bid-service → auction-service (Feign) | `auction-service` | 연결 실패, 타임아웃, 상류 5xx. 업무 4xx(404·기타)는 세지 않음 | 입찰을 즉시 503 `UPSTREAM_UNAVAILABLE`로 거절 |
| auction-service → bid-service (gRPC) | `bid-service` | 모든 gRPC 오류(`StatusRuntimeException`, 데드라인 5초 초과 포함). Feign 쪽과 달리 업무 오류를 구분하지 않는다 | 정산 스케줄러가 그 주기의 남은 정산을 중단, 다음 주기에 재시도 |

두 층은 보호 대상이 다르다. Gateway 브레이커는 클라이언트의 대기 시간을, 서비스 간 브레이커는 서비스 내부 자원(스레드·커넥션·스케줄러 주기 시간)을 지킨다. 안쪽 서비스가 "상류가 죽어서 503"이라고 응답한 것은 Gateway 입장에서 정상 응답이므로 바깥 브레이커를 열지 않는다. 결정 기록은 `tracking/decisions/0003-circuit-breaker-placement.md`.

## 데이터 소유권

| 서비스 | DB | 소유 테이블 |
|--------|-----|-----------|
| auction-service | auction_db | product, auction, outbox, processed_event, shedlock |
| bid-service | bid_db | bid |
| payment-service | payment_db | payment, outbox, shedlock |
| gateway, discovery-service | 없음 | — |

각 서비스는 자신의 DB만 접근한다. 다른 서비스의 데이터가 필요하면 API 호출이나 이벤트로 얻는다. 서비스 간 호출의 DTO는 호출하는 쪽이 자체 정의하고, common 모듈에는 이벤트 DTO와 proto만 둔다. 로컬에서는 MySQL 인스턴스 하나에 DB 3개를 둔다(결정 기록 0001).

## 동시성 제어

| 불변 조건 | 1차: 순서 처리·빠른 거절 | 최종 방어 |
|---|---|---|
| 경매당 ACTIVE 입찰 1개, 최고가만 수락 | auctionId 기준 Redisson 분산 락(D7) | `@Version` 낙관적 락(D6), 생성 컬럼 UNIQUE `uk_bid_active_auction`(D21) |
| 상품당 ACTIVE 경매 1개 | 시작 전 사전 조회 | 생성 컬럼 UNIQUE `uk_auction_active_product`(D21) |
| 마감·정산·Outbox 발행은 한 인스턴스만 | — | ShedLock(`shedlock` 테이블) |
| 같은 이벤트 두 번 처리 금지 | — | `processed_event` PK, `idempotency_key` UNIQUE |

MySQL에는 조건부 UNIQUE가 없어 `IF(status='ACTIVE', id, NULL)` 생성 컬럼에 UNIQUE를 건다. NULL끼리는 충돌하지 않으므로 ACTIVE 행끼리만 막힌다.

## 대표 흐름: 입찰

1. 클라이언트 → Gateway `POST /api/v1/bids` (`Authorization: Bearer <JWT>`, auctionId, amount)
2. Gateway가 토큰을 검증하고 `X-User-Id`를 붙여 `lb://bid-service`로 전달
3. `BidService.prepareBid` — 락·트랜잭션 없이 auction-service에서 경매를 조회하고 판매자 본인·ACTIVE 여부를 검사
4. `BidLockFacade` — `bid:auction:{auctionId}` 분산 락 획득(대기 3초, 임대 5초)
5. `BidService.placeBid` — 짧은 트랜잭션. 현재 시각으로 마감 검사 → 금액 규칙 → 이전 ACTIVE를 OUTBID로 UPDATE·flush → 새 입찰 INSERT
6. 락 해제, 201 응답. 현재 최고가는 bid-service가 소유하고 auction-service에는 아무것도 저장되지 않는다.

3단계를 락·트랜잭션 밖에 둔 이유: 원격 조회가 트랜잭션 안에 있으면 응답을 기다리는 동안 DB 커넥션을 붙잡아, 부하 시 커넥션 풀(10개)이 먼저 고갈됐다(D20 측정).

## 대표 흐름: 마감 → 낙찰 → 결제 (Choreography Saga)

1. **마감** — auction-service 스케줄러(5초 주기, ShedLock)가 end_time이 지난 ACTIVE 경매를 하나씩 CLOSED로 바꾸고 `AuctionClosedEvent`를 outbox에 기록
2. **낙찰 확정** — CLOSED이고 낙찰자 없는 경매(한 주기 최대 50개)마다 bid-service gRPC `ConfirmWinner`(트랜잭션 밖). 입찰이 없으면 FAILED(유찰)
3. 낙찰자가 있으면 `winner_id`를 기록하고 `AuctionWonEvent`를 outbox에 기록(한 트랜잭션). 경매는 CLOSED 유지
4. **결제** — payment-service가 이벤트를 받아 `{auctionId}-{winnerId}-{reassignmentCount}` 멱등키로 결제를 만들고 시뮬레이션. 결과를 `PaymentCompletedEvent` 또는 `PaymentFailedEvent`로 outbox에 기록
5. **완료** — auction-service가 `PaymentCompletedEvent`를 받으면 COMPLETED
6. **보상(차순위 승계)** — `PaymentFailedEvent`를 받으면 bid-service gRPC `ReassignWinner`로 실패자를 DISQUALIFIED, 다음 최고가 OUTBID를 WINNER로 올리고 `WinnerReassignedEvent` 발행 → 4로 돌아간다. 남은 입찰이 없으면 FAILED

경매 상태: `WAITING → ACTIVE → CLOSED → COMPLETED | FAILED`. 상태 전이 전체는 `business-rules.md`.

## 배포 구성

| 환경 | 구성 |
|---|---|
| 로컬 개발 | `docker compose up -d`로 인프라(MySQL, Redis, Kafka+Zookeeper, Kafka UI, Zipkin, Prometheus, Grafana)를 띄우고 서비스는 `bootRun`. compose에 서비스 5개 컨테이너도 들어 있다(D14) |
| 부하 테스트 | compose + `infra/k6/compose.loadtest.yml` 오버레이로 전체 스택과 k6를 같은 Docker 네트워크에서 실행(D20) |
| CI | `.github/workflows/ci.yml` — `./gradlew clean build` + 이미지 5개 빌드 |
| CD | `.github/workflows/cd.yml` — main push 시 Artifact Registry로 push(git SHA 태그) → `helm upgrade --install`로 GKE 배포. 비밀값은 GitHub Secrets에서 `--set-string`으로 주입(D19). GCP 프로젝트를 아직 만들지 않아 실제 배포는 되지 않는다 |
| 클러스터 | Terraform(`infra/terraform/`): VPC + GKE Autopilot + Artifact Registry(D16). Helm 차트(`infra/helm/auction-platform/`)가 서비스 5개의 Deployment·Service·ConfigMap·Secret을 만든다(D15). Eureka(discovery-service)도 함께 배포된다 |

## 관측성

| 신호 | 수단 | 보는 곳 |
|---|---|---|
| 트레이스 | Micrometer Tracing + OpenTelemetry → Zipkin. HTTP·Feign·gRPC·Kafka·JDBC 자동 계측, 샘플링 100%(D17) | Zipkin `localhost:9411` |
| 로그 상관관계 | 로그 패턴에 traceId·spanId | 각 서비스 로그 |
| 메트릭 | `/actuator/prometheus` → Prometheus 15초 스크래핑 → Grafana 대시보드(요청률·오류율·p95/p99·JVM·HikariCP·브레이커 상태)(D18) | Grafana `localhost:3000` |
| 브레이커 상태 | Actuator `circuitbreakers` | `operations.md` 참고 |

알림(Alertmanager)과 로그 수집(Loki)은 아직 없다.
