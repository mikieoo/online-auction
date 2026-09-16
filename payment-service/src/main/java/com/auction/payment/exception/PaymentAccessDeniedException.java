package com.auction.payment.exception;

import org.springframework.http.HttpStatus;

public class PaymentAccessDeniedException extends PaymentException {

    public PaymentAccessDeniedException(Long paymentId) {
        super(HttpStatus.FORBIDDEN, "FORBIDDEN", "본인의 결제만 조회할 수 있습니다. id=" + paymentId);
    }
}
