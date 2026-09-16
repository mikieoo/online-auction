package com.auction.payment.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 결정적 결제 시뮬레이터.
 * 금액의 정수부(원)를 10진 문자열로 보았을 때 설정된 접미사로 끝나면 실패, 아니면 성공.
 * 소수부는 무시한다. 예: 19999 → 실패, 20000 → 성공, 19999.50 → 실패.
 */
@Component
public class PaymentSimulator {

    public static final String FAILURE_REASON = "SIMULATED_FAILURE";

    private final String failureSuffix;

    public PaymentSimulator(@Value("${payment.simulation.failure-suffix:9999}") String failureSuffix) {
        if (failureSuffix == null || failureSuffix.isBlank()) {
            throw new IllegalArgumentException("payment.simulation.failure-suffix는 비어 있을 수 없습니다.");
        }
        this.failureSuffix = failureSuffix;
    }

    public boolean isFailure(BigDecimal amount) {
        String integerPart = amount.toBigInteger().toString();
        return integerPart.endsWith(failureSuffix);
    }
}
