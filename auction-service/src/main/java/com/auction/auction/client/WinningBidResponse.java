package com.auction.auction.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 낙찰 확정 응답의 winningBid 항목. 정산에 필요한 bidderId·amount 외 필드는 참고용으로만 매핑한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WinningBidResponse {

    private Long bidId;
    private Long auctionId;
    private Long bidderId;
    private BigDecimal amount;
    private String status;
    private LocalDateTime createdAt;

    protected WinningBidResponse() {
    }

    public WinningBidResponse(Long bidId, Long auctionId, Long bidderId, BigDecimal amount,
                              String status, LocalDateTime createdAt) {
        this.bidId = bidId;
        this.auctionId = auctionId;
        this.bidderId = bidderId;
        this.amount = amount;
        this.status = status;
        this.createdAt = createdAt;
    }

    public Long getBidId() { return bidId; }
    public Long getAuctionId() { return auctionId; }
    public Long getBidderId() { return bidderId; }
    public BigDecimal getAmount() { return amount; }
    public String getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
