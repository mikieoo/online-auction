package com.auction.auction.client;

import java.math.BigDecimal;

/**
 * payment-service POST /internal/v1/payments 요청 body.
 * idempotencyKey는 "{auctionId}-{winnerId}-{reassignmentCount}" 형식으로, 같은 낙찰자에 대한
 * 재시도는 같은 키가 되고 재지정(D10) 시에는 새 키가 되어 새 결제가 만들어진다.
 */
public class PaymentRequest {

    private final Long auctionId;
    private final Long payerId;
    private final BigDecimal amount;
    private final String idempotencyKey;

    public PaymentRequest(Long auctionId, Long payerId, BigDecimal amount, String idempotencyKey) {
        this.auctionId = auctionId;
        this.payerId = payerId;
        this.amount = amount;
        this.idempotencyKey = idempotencyKey;
    }

    public static String idempotencyKeyOf(Long auctionId, Long winnerId, int reassignmentCount) {
        return auctionId + "-" + winnerId + "-" + reassignmentCount;
    }

    public Long getAuctionId() { return auctionId; }
    public Long getPayerId() { return payerId; }
    public BigDecimal getAmount() { return amount; }
    public String getIdempotencyKey() { return idempotencyKey; }
}
