package com.auction.auction.exception;

import org.springframework.http.HttpStatus;

/** 404 — 리소스 없음. 코드 예: PRODUCT_NOT_FOUND, AUCTION_NOT_FOUND */
public class NotFoundException extends AuctionServiceException {

    public NotFoundException(String code, String message) {
        super(HttpStatus.NOT_FOUND, code, message);
    }
}
