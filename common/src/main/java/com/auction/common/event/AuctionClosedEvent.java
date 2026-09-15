package com.auction.common.event;

import java.time.LocalDateTime;

public class AuctionClosedEvent {

    private String eventId;
    private LocalDateTime occurredAt;
    private Long auctionId;

    public AuctionClosedEvent() {
    }

    public AuctionClosedEvent(String eventId, LocalDateTime occurredAt, Long auctionId) {
        this.eventId = eventId;
        this.occurredAt = occurredAt;
        this.auctionId = auctionId;
    }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
    public Long getAuctionId() { return auctionId; }
    public void setAuctionId(Long auctionId) { this.auctionId = auctionId; }
}
