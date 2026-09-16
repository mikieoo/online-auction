package com.auction.payment.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "auction_id", nullable = false)
    private Long auctionId;

    @Column(name = "payer_id", nullable = false)
    private Long payerId;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 64)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Payment() {
    }

    public Payment(Long auctionId, Long payerId, BigDecimal amount, String idempotencyKey) {
        this.auctionId = auctionId;
        this.payerId = payerId;
        this.amount = amount;
        this.idempotencyKey = idempotencyKey;
        this.status = PaymentStatus.REQUESTED;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public void complete() {
        if (this.status != PaymentStatus.REQUESTED) {
            throw new IllegalStateException(
                    "REQUESTED 상태에서만 완료 처리할 수 있습니다. 현재 상태: " + this.status);
        }
        this.status = PaymentStatus.COMPLETED;
        this.updatedAt = LocalDateTime.now();
    }

    public void fail(String reason) {
        if (this.status != PaymentStatus.REQUESTED) {
            throw new IllegalStateException(
                    "REQUESTED 상태에서만 실패 처리할 수 있습니다. 현재 상태: " + this.status);
        }
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isRequested() {
        return this.status == PaymentStatus.REQUESTED;
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
