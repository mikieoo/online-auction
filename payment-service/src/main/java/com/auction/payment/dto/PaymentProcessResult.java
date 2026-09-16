package com.auction.payment.dto;

import com.auction.payment.domain.Payment;

/**
 * 결제 요청 처리 결과. created가 true면 이번 요청으로 새로 생성된 결제(201),
 * false면 같은 idempotencyKey로 이미 존재하던 결제를 돌려준 것(200).
 */
public class PaymentProcessResult {

    private final Payment payment;
    private final boolean created;

    private PaymentProcessResult(Payment payment, boolean created) {
        this.payment = payment;
        this.created = created;
    }

    public static PaymentProcessResult created(Payment payment) {
        return new PaymentProcessResult(payment, true);
    }

    public static PaymentProcessResult existing(Payment payment) {
        return new PaymentProcessResult(payment, false);
    }

    public Payment getPayment() { return payment; }
    public boolean isCreated() { return created; }
}
