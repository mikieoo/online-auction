package com.auction.bid.dto;

import com.auction.bid.domain.Bid;
import com.auction.bid.domain.BidStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class BidResponse {

    private Long bidId;
    private Long auctionId;
    private Long bidderId;
    private BigDecimal amount;
    private BidStatus status;
    private LocalDateTime createdAt;

    public static BidResponse from(Bid bid) {
        BidResponse response = new BidResponse();
        response.bidId = bid.getBidId();
        response.auctionId = bid.getAuctionId();
        response.bidderId = bid.getBidderId();
        response.amount = bid.getAmount();
        response.status = bid.getStatus();
        response.createdAt = bid.getCreatedAt();
        return response;
    }

    public Long getBidId() { return bidId; }
    public Long getAuctionId() { return auctionId; }
    public Long getBidderId() { return bidderId; }
    public BigDecimal getAmount() { return amount; }
    public BidStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
