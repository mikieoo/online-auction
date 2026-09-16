package com.auction.bid.exception;

import org.springframework.http.HttpStatus;

public class AuctionNotActiveException extends BidException {

    public AuctionNotActiveException(Long auctionId, String currentStatus) {
        super(HttpStatus.CONFLICT, "AUCTION_NOT_ACTIVE",
                "진행 중인 경매에만 입찰할 수 있습니다. auctionId=" + auctionId
                        + ", 현재 상태: " + currentStatus);
    }
}
