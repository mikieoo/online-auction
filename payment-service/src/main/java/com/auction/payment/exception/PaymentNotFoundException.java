package com.auction.payment.exception;

public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(Long paymentId) {
        super("결제를 찾을 수 없습니다. id=" + paymentId);
    }
}
