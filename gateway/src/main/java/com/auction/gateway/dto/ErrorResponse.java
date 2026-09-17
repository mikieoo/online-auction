package com.auction.gateway.dto;

/** Gateway가 직접 만드는 모든 오류의 본문. 내부 서비스의 오류 본문과 같은 모양이다. */
public class ErrorResponse {

    private final String code;
    private final String message;

    public ErrorResponse(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
