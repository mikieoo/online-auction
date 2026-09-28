# D13 — Retry / DLQ (Dead Letter Queue)

## 왜 필요한가

D12까지 모든 컨슈머가 try-catch로 예외를 삼켰다.
- 예외가 발생해도 오프셋이 커밋되므로 **메시지 영구 유실**
- 일시적 실패(DB 커넥션 끊김, gRPC 타임아웃)도 재시도 없이 소실
- 장애 원인을 추적할 방법 없음

필요한 것:
1. **재시도** — 일시적 실패에 대해 일정 횟수 자동 재처리
2. **DLQ** — 재시도 소진 후 실패 메시지를 별도 토픽에 보관 (수동 복구 가능)

## 핵심 변경: try-catch 제거

```
Before (D12):
try {
    paymentService.process(request);
} catch (Exception e) {
    log.error("결제 처리 실패", e);  ← 삼킴! 오프셋 커밋 → 메시지 유실
}

After (D13):
paymentService.process(request);  ← 예외 전파 → Spring Kafka 에러 핸들러가 처리
```

예외를 삼키지 않아야 Spring Kafka의 `DefaultErrorHandler`가 재시도와 DLQ를 수행할 수 있다.

## 구현 방식: DefaultErrorHandler + DeadLetterPublishingRecoverer

### 왜 DefaultErrorHandler인가 (@RetryableTopic과 비교)

| 기준 | DefaultErrorHandler | @RetryableTopic |
|---|---|---|
| 재시도 위치 | 같은 파티션 내부 | 별도 retry 토픽 |
| 순서 보장 | O (파티션 내 순서 유지) | X (토픽이 달라짐) |
| 설정 복잡도 | 낮음 | 높음 (retry 토픽 자동 생성) |
| 적합한 경우 | 순서 중요, 재시도 횟수 적음 | 순서 무관, 장시간 지연 재시도 |

경매 이벤트는 auctionId로 파티셔닝되어 **같은 경매의 이벤트가 순서대로** 처리되어야 한다.
@RetryableTopic은 별도 토픽으로 보내므로 순서가 깨진다 → DefaultErrorHandler 선택.

### KafkaConsumerConfig 구성

```
ConcurrentKafkaListenerContainerFactory
├─ ConsumerFactory (기존 설정)
└─ DefaultErrorHandler
    ├─ DeadLetterPublishingRecoverer(kafkaTemplate)
    │   └─ 실패 메시지를 <원본토픽>.DLT 토픽으로 발행
    ├─ FixedBackOff(1000L, 2)
    │   └─ 1초 간격, 최대 2회 재시도 (총 3회 시도)
    ├─ addNotRetryableExceptions(DeserializationException.class)
    │   └─ 역직렬화 오류는 재시도 무의미 → 즉시 DLQ
    └─ RetryListener
        └─ 재시도 시 로그: "재시도 {}/3. topic={}, offset={}, exception={}"
```

### DLQ 토픽 네이밍

Spring Kafka 기본값: `<원본토픽>.DLT` (Dead Letter Topic)
- `auction-events` → `auction-events.DLT`
- `payment-events` → `payment-events.DLT`

### 역직렬화 오류 즉시 DLQ 전송 이유

- 메시지 페이로드가 깨진 것이므로 재시도해도 같은 결과
- 재시도하면 다른 정상 메시지의 처리가 지연됨
- `DeserializationException`을 not-retryable로 등록

## D12 멱등 컨슈머와의 관계

| 계층 | 역할 | 메커니즘 |
|---|---|---|
| D13 Retry/DLQ | 일시적 실패 복구 | DefaultErrorHandler가 예외 캐치 → 재시도 → DLQ |
| D12 멱등 컨슈머 | 중복 처리 방지 | processed_event 테이블로 같은 eventId 건너뜀 |

재시도 시 멱등 컨슈머가 있으므로:
- 1차 시도에서 DB 쓰기까지 성공 후 오프셋 커밋 전에 죽으면 → 재시도 시 멱등키로 건너뜀
- 1차 시도에서 DB 쓰기 전 실패(gRPC 타임아웃 등)이면 → 재시도 시 정상 처리

## 변경된 파일

- `auction-service/config/KafkaConsumerConfig.java` — 신규
- `payment-service/config/KafkaConsumerConfig.java` — 신규
- `auction-service/event/PaymentEventConsumer.java` — try-catch 제거
- `payment-service/event/AuctionWonEventConsumer.java` — try-catch 제거
- 테스트: `exceptionSwallowed` → `exceptionPropagated` (예외 전파 검증)

## 면접 포인트

1. **왜 예외를 삼키면 안 되나** — 오프셋이 커밋되어 메시지가 영구 유실. 재시도/DLQ 불가능
2. **DefaultErrorHandler vs @RetryableTopic** — 파티션 순서 보장이 필요하면 DefaultErrorHandler
3. **DLQ 운영** — DLQ에 쌓인 메시지를 모니터링하고, 원인 수정 후 원본 토픽에 재발행하거나 수동 처리
4. **역직렬화 오류는 왜 재시도하지 않나** — 페이로드 자체가 깨진 것이므로 재시도해도 동일 실패
5. **Retry + 멱등의 조합** — at-least-once 전달(Outbox) + 재시도(Retry/DLQ) + 멱등 컨슈머 = 메시지 유실 없는 안정적 처리
