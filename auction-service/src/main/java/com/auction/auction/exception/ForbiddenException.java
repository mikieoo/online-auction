package com.auction.auction.exception;

import org.springframework.http.HttpStatus;

/** 403 — 타인의 상품/경매에 대한 조작. 코드는 항상 FORBIDDEN */
public class ForbiddenException extends AuctionServiceException {

    public static final String CODE = "FORBIDDEN";

    public ForbiddenException(String message) {
        super(HttpStatus.FORBIDDEN, CODE, message);
    }
}
