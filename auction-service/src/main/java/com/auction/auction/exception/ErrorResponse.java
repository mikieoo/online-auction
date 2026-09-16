package com.auction.auction.exception;

/**
 * 서비스 공통 표준 오류 응답 바디.
 * 예: { "code": "AUCTION_NOT_FOUND", "message": "경매를 찾을 수 없습니다. id=1" }
 */
public class ErrorResponse {

    private final String code;
    private final String message;

    public ErrorResponse(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String getCode() { return code; }
    public String getMessage() { return message; }
}
