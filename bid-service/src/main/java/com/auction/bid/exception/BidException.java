package com.auction.bid.exception;

import org.springframework.http.HttpStatus;

/**
 * bid-service 도메인 예외의 공통 부모. HTTP 상태와 UPPER_SNAKE_CASE 에러 코드를 함께 가진다.
 */
public abstract class BidException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected BidException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    protected BidException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}
