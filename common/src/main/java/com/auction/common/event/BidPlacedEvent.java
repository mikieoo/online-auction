package com.auction.common.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class BidPlacedEvent {

    private String eventId;
    private LocalDateTime occurredAt;
    private Long auctionId;
    private Long bidId;
    private Long bidderId;
    private BigDecimal amount;

    public BidPlacedEvent() {
    }

    public BidPlacedEvent(String eventId, LocalDateTime occurredAt, Long auctionId,
                           Long bidId, Long bidderId, BigDecimal amount) {
        this.eventId = eventId;
        this.occurredAt = occurredAt;
        this.auctionId = auctionId;
        this.bidId = bidId;
        this.bidderId = bidderId;
        this.amount = amount;
    }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
    public Long getAuctionId() { return auctionId; }
    public void setAuctionId(Long auctionId) { this.auctionId = auctionId; }
    public Long getBidId() { return bidId; }
    public void setBidId(Long bidId) { this.bidId = bidId; }
    public Long getBidderId() { return bidderId; }
    public void setBidderId(Long bidderId) { this.bidderId = bidderId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}
