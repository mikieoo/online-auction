package com.auction.bid.exception;

import org.springframework.http.HttpStatus;

public class AuctionNotFoundException extends BidException {

    public AuctionNotFoundException(Long auctionId) {
        super(HttpStatus.NOT_FOUND, "AUCTION_NOT_FOUND",
                "경매를 찾을 수 없습니다. auctionId=" + auctionId);
    }
}
