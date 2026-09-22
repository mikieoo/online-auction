package com.auction.bid.exception;

import org.springframework.http.HttpStatus;

public class BidConflictException extends BidException {

    public BidConflictException(Long auctionId) {
        super(HttpStatus.CONFLICT, "BID_CONFLICT",
                "다른 입찰과 동시에 처리되어 실패했습니다. 다시 시도해 주세요. auctionId=" + auctionId);
    }
}
