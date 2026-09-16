package com.auction.payment.exception;

public class PaymentAccessDeniedException extends RuntimeException {

    public PaymentAccessDeniedException(Long paymentId) {
        super("본인의 결제만 조회할 수 있습니다. id=" + paymentId);
    }
}
