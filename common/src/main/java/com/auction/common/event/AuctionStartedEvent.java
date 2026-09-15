package com.auction.common.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class AuctionStartedEvent {

    private String eventId;
    private LocalDateTime occurredAt;
    private Long auctionId;
    private Long productId;
    private BigDecimal startingPrice;
    private LocalDateTime endTime;

    public AuctionStartedEvent() {
    }

    public AuctionStartedEvent(String eventId, LocalDateTime occurredAt, Long auctionId,
                                Long productId, BigDecimal startingPrice, LocalDateTime endTime) {
        this.eventId = eventId;
        this.occurredAt = occurredAt;
        this.auctionId = auctionId;
        this.productId = productId;
        this.startingPrice = startingPrice;
        this.endTime = endTime;
    }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
    public Long getAuctionId() { return auctionId; }
    public void setAuctionId(Long auctionId) { this.auctionId = auctionId; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public BigDecimal getStartingPrice() { return startingPrice; }
    public void setStartingPrice(BigDecimal startingPrice) { this.startingPrice = startingPrice; }
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
}
