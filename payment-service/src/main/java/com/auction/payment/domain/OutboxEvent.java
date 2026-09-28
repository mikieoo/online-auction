package com.auction.payment.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Outbox 테이블 엔티티. 비즈니스 트랜잭션과 같은 커밋에서 저장되어
 * DB 트랜잭션과 이벤트 발행의 원자성을 보장한다.
 */
@Entity
@Table(name = "outbox")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "outbox_id")
    private Long outboxId;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "JSON")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "published", nullable = false)
    private boolean published;

    protected OutboxEvent() {
    }

    public static OutboxEvent create(String aggregateType, Long aggregateId,
                                      String eventType, String payload) {
        OutboxEvent event = new OutboxEvent();
        event.aggregateType = aggregateType;
        event.aggregateId = aggregateId;
        event.eventType = eventType;
        event.payload = payload;
        event.createdAt = LocalDateTime.now();
        event.published = false;
        return event;
    }

    public void markPublished() {
        this.published = true;
    }

    public Long getOutboxId() { return outboxId; }
    public String getAggregateType() { return aggregateType; }
    public Long getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public boolean isPublished() { return published; }
}
