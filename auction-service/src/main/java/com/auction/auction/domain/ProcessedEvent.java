package com.auction.auction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 멱등 컨슈머용 처리 완료 기록.
 * eventId(PK)로 중복 처리를 차단한다.
 */
@Entity
@Table(name = "processed_event")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", length = 64)
    private String eventId;

    @Column(name = "handler_name", nullable = false, length = 100)
    private String handlerName;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    protected ProcessedEvent() {
    }

    public ProcessedEvent(String eventId, String handlerName, LocalDateTime processedAt) {
        this.eventId = eventId;
        this.handlerName = handlerName;
        this.processedAt = processedAt;
    }

    public String getEventId() { return eventId; }
    public String getHandlerName() { return handlerName; }
    public LocalDateTime getProcessedAt() { return processedAt; }
}
