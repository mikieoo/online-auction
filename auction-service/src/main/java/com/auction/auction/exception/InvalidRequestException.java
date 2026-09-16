package com.auction.auction.exception;

import org.springframework.http.HttpStatus;

/** 400 — 요청 값 오류. 코드는 항상 INVALID_REQUEST */
public class InvalidRequestException extends AuctionServiceException {

    public static final String CODE = "INVALID_REQUEST";

    public InvalidRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, CODE, message);
    }
}
