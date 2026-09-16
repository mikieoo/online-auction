package com.auction.bid.exception;

import org.springframework.http.HttpStatus;

public class BidNotFoundException extends BidException {

    public BidNotFoundException(Long bidId) {
        super(HttpStatus.NOT_FOUND, "BID_NOT_FOUND",
                "입찰을 찾을 수 없습니다. bidId=" + bidId);
    }
}
