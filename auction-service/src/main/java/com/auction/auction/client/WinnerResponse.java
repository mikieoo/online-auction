package com.auction.auction.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * bid-service POST /internal/v1/bids/auctions/{auctionId}/winner 응답.
 * bid-service의 DTO를 공유하지 않고 JSON 계약(키 이름)에만 의존한다.
 * hasBids=false이면 winningBid는 null이다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WinnerResponse {

    private Long auctionId;
    private boolean hasBids;
    private WinningBidResponse winningBid;

    protected WinnerResponse() {
    }

    public WinnerResponse(Long auctionId, boolean hasBids, WinningBidResponse winningBid) {
        this.auctionId = auctionId;
        this.hasBids = hasBids;
        this.winningBid = winningBid;
    }

    public Long getAuctionId() { return auctionId; }
    public boolean isHasBids() { return hasBids; }
    public WinningBidResponse getWinningBid() { return winningBid; }
}
