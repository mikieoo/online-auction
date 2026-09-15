package com.auction.common.event;

import java.time.LocalDateTime;

public class PaymentCompletedEvent {

    private String eventId;
    private LocalDateTime occurredAt;
    private Long auctionId;
    private Long paymentId;

    public PaymentCompletedEvent() {
    }

    public PaymentCompletedEvent(String eventId, LocalDateTime occurredAt,
                                  Long auctionId, Long paymentId) {
        this.eventId = eventId;
        this.occurredAt = occurredAt;
        this.auctionId = auctionId;
        this.paymentId = paymentId;
    }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
    public Long getAuctionId() { return auctionId; }
    public void setAuctionId(Long auctionId) { this.auctionId = auctionId; }
    public Long getPaymentId() { return paymentId; }
    public void setPaymentId(Long paymentId) { this.paymentId = paymentId; }
}
