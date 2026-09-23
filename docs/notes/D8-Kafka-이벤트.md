# D8: Kafka 이벤트 — 경매 시작·마감 이벤트 발행과 소비

## 오늘 한 일

auction-service에서 경매 시작(AuctionStartedEvent)과 마감(AuctionClosedEvent)을 Kafka `auction-events` 토픽으로 발행하고, bid-service가 이를 소비하는 파이프라인을 구축했다. D9~D13의 Saga·Outbox·멱등 컨슈머 기반이 된다.

---

## 1. 구조

```
AuctionController.startAuction()
  ├─ AuctionService.startAuction()   ← @Transactional (DB 커밋)
  └─ AuctionEventProducer.publishStarted()   ← 커밋 후 발행

AuctionSettlementScheduler.closeEndedAuctions()
  ├─ AuctionSettlementService.closeAuction()  ← @Transactional (DB 커밋)
  └─ AuctionEventProducer.publishClosed()     ← 커밋 후 발행

                      ↓ Kafka (auction-events 토픽)

AuctionEventConsumer (bid-service)
  ├─ @KafkaHandler: AuctionStartedEvent → 로그
  └─ @KafkaHandler: AuctionClosedEvent  → 로그
```

## 2. 왜 트랜잭션 밖에서 발행하나

```
❌ @Transactional 안에서 Kafka 발행
   → 커밋 전에 이벤트가 나감 → 롤백되면 phantom event

✅ 트랜잭션이 끝난 후(컨트롤러/스케줄러)에서 발행
   → 커밋이 보장된 후에만 이벤트 발행 → phantom event 없음

⚠️ 남은 문제: 커밋 후 발행 전 애플리케이션 크래시 → 이벤트 유실
   → D11 Outbox 패턴으로 해결 (DB에 이벤트 기록 → 릴레이어가 발행)
```

## 3. 핵심 코드

### 프로듀서 (auction-service)

```java
@Component
public class AuctionEventProducer {
    private static final String TOPIC = "auction-events";
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishStarted(AuctionResponse response) {
        AuctionStartedEvent event = new AuctionStartedEvent(
                UUID.randomUUID().toString(), LocalDateTime.now(),
                response.getAuctionId(), response.getProductId(),
                response.getStartingPrice(), response.getEndTime());
        send(event.getAuctionId(), event, "AuctionStartedEvent");
    }

    public void publishClosed(Long auctionId) {
        AuctionClosedEvent event = new AuctionClosedEvent(
                UUID.randomUUID().toString(), LocalDateTime.now(), auctionId);
        send(auctionId, event, "AuctionClosedEvent");
    }

    private void send(Long auctionId, Object event, String eventType) {
        kafkaTemplate.send(TOPIC, String.valueOf(auctionId), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) log.error("이벤트 발행 실패. type={}", eventType, ex);
                    else log.info("이벤트 발행 완료. type={}, offset={}", eventType, result.getRecordMetadata().offset());
                });
    }
}
```

### 컨슈머 (bid-service)

```java
@Component
@KafkaListener(topics = "auction-events", groupId = "${spring.kafka.consumer.group-id}")
public class AuctionEventConsumer {

    @KafkaHandler
    public void handleAuctionStarted(AuctionStartedEvent event) {
        log.info("경매 시작 이벤트 수신. auctionId={}", event.getAuctionId());
    }

    @KafkaHandler
    public void handleAuctionClosed(AuctionClosedEvent event) {
        log.info("경매 마감 이벤트 수신. auctionId={}", event.getAuctionId());
    }

    @KafkaHandler(isDefault = true)
    public void handleUnknown(Object event) {
        log.warn("알 수 없는 이벤트 수신. type={}", event.getClass().getName());
    }
}
```

## 4. 설정 포인트

### 프로듀서 (auction-service application.yml)

```yaml
spring.kafka:
  bootstrap-servers: localhost:9092
  producer:
    key-serializer: StringSerializer
    value-serializer: JsonSerializer
    acks: all
    properties:
      spring.json.add.type.headers: true   # 컨슈머 자동 타입 감지
```

### 컨슈머 (bid-service application.yml)

```yaml
spring.kafka:
  bootstrap-servers: localhost:9092
  consumer:
    group-id: bid-service-group
    auto-offset-reset: earliest
    key-deserializer: StringDeserializer
    value-deserializer: JsonDeserializer
    properties:
      spring.json.trusted.packages: "com.auction.common.event"
```

### 파티션 키

모든 경매 이벤트의 키가 `auctionId`이므로, 같은 경매의 이벤트는 항상 같은 파티션으로 간다 → 순서 보장.

## 5. `@KafkaHandler` vs 단일 핸들러

| 방식 | 장점 | 단점 |
|---|---|---|
| `@KafkaHandler` (타입별 메서드) | 타입 안전, 새 이벤트 타입 추가가 메서드 추가로 끝남 | type header 필수 |
| 단일 `Object` + `instanceof` | type header 없어도 동작 | 타입 체크 분기가 늘어남 |
| 토픽 분리 (이벤트별 토픽) | 컨슈머가 단순해짐 | 토픽 관리 비용 증가, 순서 보장 깨짐 |

이 프로젝트에서는 같은 `auction-events` 토픽에 여러 이벤트를 보내고 `@KafkaHandler`로 라우팅한다. D9~D10에서 `AuctionWonEvent`, `WinnerReassignedEvent`가 추가되면 핸들러 메서드만 추가하면 된다.

## 6. 면접에서 물어볼 만한 것

**Q: 왜 이벤트를 DB 트랜잭션과 함께 보내지 않나?**
- DB 커밋과 Kafka 발행은 서로 다른 시스템이라 2PC 없이는 원자적이지 않다. 트랜잭션 안에서 보내면 롤백 시 phantom event, 밖에서 보내면 크래시 시 이벤트 유실. Outbox 패턴이 표준 해법 — DB에 이벤트 행을 같은 트랜잭션에 삽입하고, 별도 릴레이어가 Kafka로 발행.

**Q: `acks=all`은 무슨 의미?**
- 리더와 모든 ISR 복제본이 메시지를 기록해야 프로듀서에게 ack를 보낸다. 가장 느리지만 가장 안전한 설정. `acks=1`(리더만)은 리더 장애 시 유실 가능, `acks=0`은 fire-and-forget.

**Q: `auto-offset-reset: earliest`의 의미?**
- 컨슈머 그룹이 처음 토픽을 구독할 때(커밋된 오프셋이 없을 때) 토픽의 처음부터 읽는다. `latest`로 하면 구독 이후의 메시지만 읽는다. 이벤트 유실을 줄이려면 `earliest`가 안전.

**Q: `spring.json.trusted.packages`는 왜 필요한가?**
- `JsonDeserializer`는 보안상 기본적으로 알 수 없는 패키지의 클래스 역직렬화를 거부한다. `trusted.packages`로 허용할 패키지를 명시해야 이벤트 DTO를 역직렬화할 수 있다. `*`로 전부 허용할 수도 있지만, 패키지를 한정하는 게 더 안전.
