package com.auction.payment.exception;

import org.springframework.http.HttpStatus;

/** payment-service 도메인 예외의 공통 부모. HTTP 상태와 오류 code를 함께 들고 있어 처리기가 일괄 매핑한다. */
public abstract class PaymentException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected PaymentException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}
