package com.auction.bid.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * auction-service GET /api/v1/auctions/{auctionId} 응답 중 bid-service가 필요한 필드만 매핑한다.
 * auction-service의 엔티티·DTO를 공유하지 않고 JSON 계약(키 이름)에만 의존한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuctionSummaryResponse {

    private Long auctionId;
    private Long sellerId;
    private BigDecimal startingPrice;
    private String status;
    private LocalDateTime endTime;

    protected AuctionSummaryResponse() {
    }

    public AuctionSummaryResponse(Long auctionId, Long sellerId, BigDecimal startingPrice,
                                  String status, LocalDateTime endTime) {
        this.auctionId = auctionId;
        this.sellerId = sellerId;
        this.startingPrice = startingPrice;
        this.status = status;
        this.endTime = endTime;
    }

    public Long getAuctionId() { return auctionId; }
    public Long getSellerId() { return sellerId; }
    public BigDecimal getStartingPrice() { return startingPrice; }
    public String getStatus() { return status; }
    public LocalDateTime getEndTime() { return endTime; }
}
