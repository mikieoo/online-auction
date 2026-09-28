# D11 — Outbox 패턴 (Polling Publisher)

## 왜 필요한가

D8~D10까지는 DB 트랜잭션 커밋 후 Kafka에 직접 발행하는 구조였다.
트랜잭션이 커밋되고 Kafka 발행 전에 앱이 죽으면 이벤트가 유실된다.
DB와 메시지 브로커는 서로 다른 시스템이라 2PC 없이는 원자적으로 묶을 수 없다.

Outbox 패턴은 이벤트를 같은 DB 트랜잭션에서 outbox 테이블에 INSERT하고,
별도 폴러가 미발행 행을 읽어 Kafka로 전송한다. DB 트랜잭션이 원자성을 보장하므로
"비즈니스 데이터는 저장됐는데 이벤트는 유실" 상황이 사라진다.

## 구현 방식: Polling Publisher

세 가지 방식(Polling, CDC, Log Tailing) 중 Polling Publisher를 선택했다.
- 인프라 추가 없음 (Debezium 등 불필요)
- 구현이 간단하고 디버깅이 쉬움
- 트래픽이 적은 시스템에서는 폴링 지연(기본 1초)이 문제되지 않음

### 단점
- 폴링 주기만큼 발행 지연 존재 (기본 1초, 설정 가능)
- 대량 이벤트 시 DB 부하 (100건 배치로 제한)
- 발행 실패 시 해당 이벤트에서 멈추고 다음 폴링에서 재시도 (순서 보장)

## 핵심 구조

```
비즈니스 로직 (@Transactional)
├─ 도메인 상태 변경 (auction.close() 등)
├─ auctionRepository.save()
└─ outboxRecorder.record("Auction", auctionId, event)  ← 같은 트랜잭션

OutboxPublisher (@Scheduled, 1초 주기)
├─ outboxRepository.findTop100ByPublishedFalseOrderByCreatedAtAsc()
├─ JSON → Object 역직렬화 (Class.forName + ObjectMapper)
├─ kafkaTemplate.send(topic, key, eventObj).get(5s)  ← 동기 전송
├─ event.markPublished()  ← dirty checking으로 UPDATE
└─ 실패 시 break → 다음 폴링에서 재시도
```

## 변경 요약

| Before (D8~D10) | After (D11) |
|---|---|
| `AuctionEventProducer.publishStarted()` — 컨트롤러에서 호출 | `outboxRecorder.record()` — `AuctionService.startAuction()` 트랜잭션 내부 |
| `AuctionEventProducer.publishClosed()` — 스케줄러에서 호출 | `outboxRecorder.record()` — `AuctionSettlementService.closeAuction()` 트랜잭션 내부 |
| `AuctionEventProducer.publishWon()` — 스케줄러에서 호출 | `outboxRecorder.record()` — `AuctionSettlementService.assignWinnerForPayment()` 트랜잭션 내부 |
| `AuctionEventProducer.publishReassigned()` — 컨슈머에서 호출 | `outboxRecorder.record()` — `AuctionSettlementService.reassignWinner()` 트랜잭션 내부 |
| `PaymentEventProducer.publishCompleted/Failed()` — 컨슈머에서 호출 | `outboxRecorder.record()` — `PaymentTransactionService.createAndSettle()/resettle()` 트랜잭션 내부 |

`AuctionEventProducer`, `PaymentEventProducer` 삭제.

## Outbox 테이블

```sql
CREATE TABLE outbox (
    outbox_id    BIGINT AUTO_INCREMENT PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id   BIGINT NOT NULL,
    event_type     VARCHAR(255) NOT NULL,   -- FQCN
    payload        TEXT NOT NULL,            -- JSON
    created_at     DATETIME(6) NOT NULL,
    published      BOOLEAN NOT NULL DEFAULT FALSE,
    INDEX idx_outbox_published (published, created_at)
);
```

## ShedLock

다중 인스턴스 배포 시 같은 outbox 행을 여러 인스턴스가 동시에 폴링하는 것을 방지.
`@SchedulerLock`으로 한 인스턴스만 outbox 발행을 실행한다.

## At-Least-Once 보장과 멱등성

Kafka 전송 성공 후 `markPublished()` 전에 앱이 죽으면 다음 폴링에서 같은 이벤트를 재전송한다.
컨슈머의 멱등성이 중복 수신을 흡수한다 (idempotencyKey 기반).

## 면접 포인트

1. **Outbox 패턴은 왜 필요한가?** — DB와 메시지 브로커는 다른 시스템이라 2PC 없이 원자적 커밋이 불가능. Outbox로 이벤트를 DB에 먼저 쓰면 DB 트랜잭션의 ACID가 원자성을 보장.
2. **Polling vs CDC** — Polling은 인프라 추가 없이 구현 가능하지만 지연 있음. CDC(Debezium)는 binlog를 읽어 실시간에 가까운 발행 가능하지만 Kafka Connect/Debezium 인프라 필요.
3. **At-least-once와 exactly-once** — Outbox는 at-least-once. exactly-once는 프로듀서 idempotent + 트랜잭셔널 프로듀서 + 컨슈머 read_committed 조합으로 가능하지만 복잡도 증가.
4. **순서 보장** — `created_at` 순 폴링 + 실패 시 break로 같은 aggregate 안에서 인과 순서 유지.
5. **outbox 테이블 크기 관리** — 발행 완료된 행은 배치 삭제 또는 파티셔닝으로 관리. 이 프로젝트에서는 미구현.
