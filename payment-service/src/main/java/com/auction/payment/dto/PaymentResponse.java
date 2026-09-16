package com.auction.payment.dto;

import com.auction.payment.domain.Payment;
import com.auction.payment.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class PaymentResponse {

    private Long paymentId;
    private Long auctionId;
    private Long payerId;
    private BigDecimal amount;
    private String idempotencyKey;
    private PaymentStatus status;
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static PaymentResponse from(Payment payment) {
        PaymentResponse response = new PaymentResponse();
        response.paymentId = payment.getPaymentId();
        response.auctionId = payment.getAuctionId();
        response.payerId = payment.getPayerId();
        response.amount = payment.getAmount();
        response.idempotencyKey = payment.getIdempotencyKey();
        response.status = payment.getStatus();
        response.failureReason = payment.getFailureReason();
        response.createdAt = payment.getCreatedAt();
        response.updatedAt = payment.getUpdatedAt();
        return response;
    }

    public Long getPaymentId() { return paymentId; }
    public Long getAuctionId() { return auctionId; }
    public Long getPayerId() { return payerId; }
    public BigDecimal getAmount() { return amount; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public PaymentStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
