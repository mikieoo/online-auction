package com.auction.bid.exception;

import org.springframework.http.HttpStatus;

public class UpstreamErrorException extends BidException {

    public UpstreamErrorException(String message) {
        super(HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR", message);
    }
}
