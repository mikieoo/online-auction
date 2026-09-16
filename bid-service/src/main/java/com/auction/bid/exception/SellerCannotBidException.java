package com.auction.bid.exception;

import org.springframework.http.HttpStatus;

public class SellerCannotBidException extends BidException {

    public SellerCannotBidException(Long auctionId) {
        super(HttpStatus.FORBIDDEN, "SELLER_CANNOT_BID",
                "판매자는 본인 경매에 입찰할 수 없습니다. auctionId=" + auctionId);
    }
}
