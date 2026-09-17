package com.auction.gateway.exception;

/**
 * 서명은 맞지만 만료된 토큰. 클라이언트가 "재발급하면 되는 경우"를 구분할 수 있도록
 * InvalidTokenException과 분리해 401 TOKEN_EXPIRED로 응답한다.
 */
public class TokenExpiredException extends RuntimeException {

    public TokenExpiredException(String message, Throwable cause) {
        super(message, cause);
    }
}
