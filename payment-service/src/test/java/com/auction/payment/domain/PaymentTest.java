package com.auction.payment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    private Payment newPayment() {
        return new Payment(1L, 7L, new BigDecimal("15000"), "1-7-0");
    }

    @Test
    @DisplayName("생성 직후 상태는 REQUESTED이다")
    void createdAsRequested() {
        Payment payment = newPayment();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
        assertThat(payment.getFailureReason()).isNull();
        assertThat(payment.getCreatedAt()).isNotNull();
        assertThat(payment.getUpdatedAt()).isEqualTo(payment.getCreatedAt());
    }

    @Test
    @DisplayName("REQUESTED → COMPLETED 전이")
    void complete() {
        Payment payment = newPayment();

        payment.complete();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    }

    @Test
    @DisplayName("REQUESTED → FAILED 전이 시 실패 사유가 기록된다")
    void fail() {
        Payment payment = newPayment();

        payment.fail("SIMULATED_FAILURE");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("SIMULATED_FAILURE");
    }

    @Test
    @DisplayName("확정된 결제는 다시 전이할 수 없다")
    void noReverseTransition() {
        Payment completed = newPayment();
        completed.complete();
        Payment failed = newPayment();
        failed.fail("SIMULATED_FAILURE");

        assertThatThrownBy(completed::complete).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> completed.fail("x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(failed::complete).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> failed.fail("x")).isInstanceOf(IllegalStateException.class);
    }
}
