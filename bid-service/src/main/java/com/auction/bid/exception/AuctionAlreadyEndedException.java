package com.auction.bid.exception;

import org.springframework.http.HttpStatus;

public class AuctionAlreadyEndedException extends BidException {

    public AuctionAlreadyEndedException(Long auctionId) {
        super(HttpStatus.CONFLICT, "AUCTION_ALREADY_ENDED",
                "이미 종료 시간이 지난 경매입니다. auctionId=" + auctionId);
    }
}
