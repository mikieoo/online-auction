package com.auction.auction.exception;

import org.springframework.http.HttpStatus;

/**
 * auction-service 도메인 예외의 공통 부모.
 * HTTP 상태와 UPPER_SNAKE_CASE 오류 코드를 함께 운반하여 GlobalExceptionHandler가
 * 표준 오류 응답 { code, message } 으로 변환한다.
 */
public abstract class AuctionServiceException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected AuctionServiceException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}
