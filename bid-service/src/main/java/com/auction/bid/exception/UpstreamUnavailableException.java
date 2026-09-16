package com.auction.bid.exception;

import org.springframework.http.HttpStatus;

public class UpstreamUnavailableException extends BidException {

    public UpstreamUnavailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM_UNAVAILABLE", message);
    }

    public UpstreamUnavailableException(String message, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM_UNAVAILABLE", message, cause);
    }
}
