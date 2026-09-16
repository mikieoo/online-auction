package com.auction.payment.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentSimulatorTest {

    private final PaymentSimulator simulator = new PaymentSimulator("9999");

    @ParameterizedTest(name = "{0}원 → 실패={1}")
    @CsvSource({
            "19999, true",
            "20000, false",
            "9999, true",
            "19999.50, true",
            "19999.99, true",
            "20000.9999, false",
            "15000, false",
            "1, false",
            "109999.00, true"
    })
    @DisplayName("금액 정수부가 접미사로 끝나면 실패, 소수부는 무시한다")
    void suffixRule(String amount, boolean expectedFailure) {
        assertThat(simulator.isFailure(new BigDecimal(amount))).isEqualTo(expectedFailure);
    }

    @Test
    @DisplayName("접미사는 설정값으로 바꿀 수 있다")
    void configurableSuffix() {
        PaymentSimulator custom = new PaymentSimulator("77");

        assertThat(custom.isFailure(new BigDecimal("1077"))).isTrue();
        assertThat(custom.isFailure(new BigDecimal("19999"))).isFalse();
    }
}
