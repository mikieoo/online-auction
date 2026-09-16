package com.auction.payment.exception;

import org.springframework.http.HttpStatus;

public class PaymentNotFoundException extends PaymentException {

    public PaymentNotFoundException(Long paymentId) {
        super(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "결제를 찾을 수 없습니다. id=" + paymentId);
    }
}
