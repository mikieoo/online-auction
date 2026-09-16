package com.auction.auction.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * payment-service POST /internal/v1/payments 응답(200 기존 반환 / 201 신규 생성 모두 같은 형태).
 * payment-service의 DTO를 공유하지 않고 JSON 계약(키 이름)에만 의존한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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

    protected PaymentResponse() {
    }

    public PaymentResponse(Long paymentId, Long auctionId, Long payerId, BigDecimal amount,
                           String idempotencyKey, PaymentStatus status, String failureReason,
                           LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.paymentId = paymentId;
        this.auctionId = auctionId;
        this.payerId = payerId;
        this.amount = amount;
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.failureReason = failureReason;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
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
