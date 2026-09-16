package com.auction.auction.dto;

import com.auction.auction.domain.Auction;
import com.auction.auction.domain.AuctionStatus;
import com.auction.auction.domain.Product;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * bid-service와 스케줄러가 의지하는 경매 응답 계약.
 * sellerId, startingPrice는 Product에서 채운다.
 */
public class AuctionResponse {

    private Long auctionId;
    private Long productId;
    private Long sellerId;
    private BigDecimal startingPrice;
    private AuctionStatus status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long winnerId;
    private BigDecimal winningPrice;
    private int reassignmentCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static AuctionResponse from(Auction auction, Product product) {
        AuctionResponse response = new AuctionResponse();
        response.auctionId = auction.getAuctionId();
        response.productId = auction.getProductId();
        response.sellerId = product.getSellerId();
        response.startingPrice = product.getStartingPrice();
        response.status = auction.getStatus();
        response.startTime = auction.getStartTime();
        response.endTime = auction.getEndTime();
        response.winnerId = auction.getWinnerId();
        response.winningPrice = auction.getWinningPrice();
        response.reassignmentCount = auction.getReassignmentCount();
        response.createdAt = auction.getCreatedAt();
        response.updatedAt = auction.getUpdatedAt();
        return response;
    }

    public Long getAuctionId() { return auctionId; }
    public Long getProductId() { return productId; }
    public Long getSellerId() { return sellerId; }
    public BigDecimal getStartingPrice() { return startingPrice; }
    public AuctionStatus getStatus() { return status; }
    public LocalDateTime getStartTime() { return startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public Long getWinnerId() { return winnerId; }
    public BigDecimal getWinningPrice() { return winningPrice; }
    public int getReassignmentCount() { return reassignmentCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
