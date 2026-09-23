# D9 — Saga: 동기 결제 → 비동기 이벤트 전환

## 핵심 변경

스케줄러가 payment-service를 동기(Feign)로 호출하던 구조를 Kafka 이벤트 기반 Saga(Choreography)로 전환.

### 흐름 변경

**Before (D3~D8):**
```
스케줄러 → [gRPC] bid-service 낙찰 확정 → [Feign] payment-service 결제 → DB 저장(COMPLETED/FAILED)
```

**After (D9):**
```
스케줄러 → [gRPC] bid-service 낙찰 확정 → DB 저장(CLOSED+winnerId) → AuctionWonEvent 발행
payment-service ← AuctionWonEvent 수신 → 결제 처리 → PaymentCompleted/FailedEvent 발행
auction-service ← PaymentCompletedEvent 수신 → COMPLETED 전이
```

## 상태 인코딩

| status | winnerId | 의미 |
|--------|----------|------|
| CLOSED | NULL | 정산 대기 (B단계 대상) |
| CLOSED | 있음 | 낙찰자 확정, 결제 대기/실패 |
| COMPLETED | 있음 | 결제 성공 |
| FAILED | NULL | 유찰 (입찰 없음) |

## 변경된 파일

### auction-service
- `AuctionSettlementService`: `completeWithWinner()`/`recordPaymentFailed()` → `assignWinnerForPayment()`/`completeAuction()`
- `AuctionSettlementScheduler`: PaymentClient 의존성 완전 제거, publishWon 추가
- `AuctionEventProducer`: `publishWon()` 추가
- `PaymentEventConsumer` (신규): payment-events 토픽 소비, PaymentCompleted → completeAuction

### payment-service
- `AuctionWonEventConsumer` (신규): auction-events 토픽 소비, PaymentService.process() 호출
- `PaymentEventProducer` (신규): payment-events 토픽에 결제 결과 발행

## 멱등성

- **AuctionWonEvent → 결제**: idempotencyKey(`{auctionId}-{winnerId}-{reassignmentCount}`)로 PaymentService가 중복 결제 방지
- **PaymentCompletedEvent → 경매 완료**: `completeAuction()`이 이미 COMPLETED이면 무시

## 남은 과제

- PaymentFailedEvent 수신 시 차순위 승계 (D10)
- DB 트랜잭션과 이벤트 발행의 원자성 (D11 Outbox)
- 컨슈머 재시도/DLQ (D12~D13)

## 면접 포인트

- **왜 Choreography?** 서비스가 2개뿐(auction↔payment). Orchestrator를 두면 오히려 단일 장애점이 생긴다.
- **이벤트 유실은?** 아직 at-most-once. D11 Outbox로 at-least-once 보장 후 멱등 컨슈머와 조합.
- **idempotencyKey 설계**: 재지정(reassignment)마다 키가 달라져야 같은 경매의 다른 낙찰자 결제가 별도로 처리됨.

## 테스트

- 전체 220개 (auction 76, bid 72, payment 44 (+7), gateway 28)
- payment-service 신규: PaymentEventProducerTest(2), AuctionWonEventConsumerTest(5)
- auction-service 변경: AuctionSettlementSchedulerTest(전면 재작성), AuctionSettlementServiceTest(assignWinnerForPayment/completeAuction 추가), AuctionEventProducerTest(publishWon 추가), PaymentEventConsumerTest(4)
