package com.auction.common.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class WinnerReassignedEvent {

    private String eventId;
    private LocalDateTime occurredAt;
    private Long auctionId;
    private Long newWinnerId;
    private BigDecimal newWinningPrice;
    private String idempotencyKey;

    public WinnerReassignedEvent() {
    }

    public WinnerReassignedEvent(String eventId, LocalDateTime occurredAt, Long auctionId,
                                  Long newWinnerId, BigDecimal newWinningPrice, String idempotencyKey) {
        this.eventId = eventId;
        this.occurredAt = occurredAt;
        this.auctionId = auctionId;
        this.newWinnerId = newWinnerId;
        this.newWinningPrice = newWinningPrice;
        this.idempotencyKey = idempotencyKey;
    }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
    public Long getAuctionId() { return auctionId; }
    public void setAuctionId(Long auctionId) { this.auctionId = auctionId; }
    public Long getNewWinnerId() { return newWinnerId; }
    public void setNewWinnerId(Long newWinnerId) { this.newWinnerId = newWinnerId; }
    public BigDecimal getNewWinningPrice() { return newWinningPrice; }
    public void setNewWinningPrice(BigDecimal newWinningPrice) { this.newWinningPrice = newWinningPrice; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
