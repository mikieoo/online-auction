package com.auction.bid.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public class PlaceBidRequest {

    @NotNull(message = "auctionId는 필수입니다.")
    private Long auctionId;

    @NotNull(message = "amount는 필수입니다.")
    @Positive(message = "amount는 0보다 커야 합니다.")
    private BigDecimal amount;

    public Long getAuctionId() { return auctionId; }
    public void setAuctionId(Long auctionId) { this.auctionId = auctionId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}
