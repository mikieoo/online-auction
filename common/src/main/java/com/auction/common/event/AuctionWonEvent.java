package com.auction.common.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class AuctionWonEvent {

    private String eventId;
    private LocalDateTime occurredAt;
    private Long auctionId;
    private Long winnerId;
    private BigDecimal winningPrice;
    private String idempotencyKey;

    public AuctionWonEvent() {
    }

    public AuctionWonEvent(String eventId, LocalDateTime occurredAt, Long auctionId,
                            Long winnerId, BigDecimal winningPrice, String idempotencyKey) {
        this.eventId = eventId;
        this.occurredAt = occurredAt;
        this.auctionId = auctionId;
        this.winnerId = winnerId;
        this.winningPrice = winningPrice;
        this.idempotencyKey = idempotencyKey;
    }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
    public Long getAuctionId() { return auctionId; }
    public void setAuctionId(Long auctionId) { this.auctionId = auctionId; }
    public Long getWinnerId() { return winnerId; }
    public void setWinnerId(Long winnerId) { this.winnerId = winnerId; }
    public BigDecimal getWinningPrice() { return winningPrice; }
    public void setWinningPrice(BigDecimal winningPrice) { this.winningPrice = winningPrice; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
