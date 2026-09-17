package com.auction.gateway.exception;

/** 토큰 형식·서명·sub 값이 올바르지 않을 때. 응답 코드는 401 UNAUTHORIZED. */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message, Throwable cause) {
        super(message, cause);
    }

    public InvalidTokenException(String message) {
        super(message);
    }
}
