package com.auction.bid.exception;

import org.springframework.http.HttpStatus;

public class InvalidRequestException extends BidException {

    public InvalidRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }
}
