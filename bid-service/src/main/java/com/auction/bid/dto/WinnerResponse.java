package com.auction.bid.dto;

import com.auction.bid.domain.Bid;

/**
 * 낙찰 확정 응답. 입찰이 없는 경우도 HTTP 200으로 표현하며 hasBids=false, winningBid=null.
 */
public class WinnerResponse {

    private Long auctionId;
    private boolean hasBids;
    private BidResponse winningBid;

    public static WinnerResponse of(Long auctionId, Bid winner) {
        WinnerResponse response = new WinnerResponse();
        response.auctionId = auctionId;
        response.hasBids = true;
        response.winningBid = BidResponse.from(winner);
        return response;
    }

    public static WinnerResponse noBids(Long auctionId) {
        WinnerResponse response = new WinnerResponse();
        response.auctionId = auctionId;
        response.hasBids = false;
        response.winningBid = null;
        return response;
    }

    public Long getAuctionId() { return auctionId; }
    public boolean isHasBids() { return hasBids; }
    public BidResponse getWinningBid() { return winningBid; }
}
