package com.auction.payment.event;

import com.auction.common.event.AuctionWonEvent;
import com.auction.common.event.WinnerReassignedEvent;
import com.auction.payment.domain.Payment;
import com.auction.payment.dto.PaymentProcessResult;
import com.auction.payment.service.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuctionWonEventConsumerTest {

    @Mock
    private PaymentService paymentService;

    @InjectMocks
    private AuctionWonEventConsumer consumer;

    private AuctionWonEvent sampleEvent() {
        return new AuctionWonEvent(
                "evt-1", LocalDateTime.now(),
                100L, 77L, new BigDecimal("15000.00"), "100-77-0");
    }

    private Payment completedPayment() {
        Payment payment = new Payment(100L, 77L, new BigDecimal("15000.00"), "100-77-0");
        ReflectionTestUtils.setField(payment, "paymentId", 900L);
        payment.complete();
        return payment;
    }

    // ---------- AuctionWonEvent ----------

    @Test
    @DisplayName("AuctionWonEvent 수신 시 결제를 처리한다 (outbox는 서비스 내부에서 기록)")
    void handleAuctionWon_callsPaymentService() {
        Payment payment = completedPayment();
        when(paymentService.process(any())).thenReturn(PaymentProcessResult.created(payment));

        consumer.handleAuctionWon(sampleEvent());

        ArgumentCaptor<com.auction.payment.dto.PaymentRequest> captor =
                ArgumentCaptor.forClass(com.auction.payment.dto.PaymentRequest.class);
        verify(paymentService).process(captor.capture());
        assertThat(captor.getValue().getAuctionId()).isEqualTo(100L);
        assertThat(captor.getValue().getPayerId()).isEqualTo(77L);
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("15000.00");
        assertThat(captor.getValue().getIdempotencyKey()).isEqualTo("100-77-0");
    }

    @Test
    @DisplayName("PaymentService 예외 발생 시 전파된다 (DefaultErrorHandler 재시도/DLQ)")
    void handleAuctionWon_exceptionPropagated() {
        when(paymentService.process(any())).thenThrow(new RuntimeException("DB error"));

        assertThatThrownBy(() -> consumer.handleAuctionWon(sampleEvent()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB error");
    }

    // ---------- WinnerReassignedEvent ----------

    @Test
    @DisplayName("차순위 승계 이벤트 수신 시 결제를 처리한다 (outbox는 서비스 내부에서 기록)")
    void handleWinnerReassigned_callsPaymentService() {
        WinnerReassignedEvent event = new WinnerReassignedEvent(
                "evt-r1", LocalDateTime.now(), 100L, 88L, new BigDecimal("12000.00"), "100-88-1");
        Payment payment = new Payment(100L, 88L, new BigDecimal("12000.00"), "100-88-1");
        ReflectionTestUtils.setField(payment, "paymentId", 901L);
        payment.complete();

        when(paymentService.process(any())).thenReturn(PaymentProcessResult.created(payment));

        consumer.handleWinnerReassigned(event);

        ArgumentCaptor<com.auction.payment.dto.PaymentRequest> captor =
                ArgumentCaptor.forClass(com.auction.payment.dto.PaymentRequest.class);
        verify(paymentService).process(captor.capture());
        assertThat(captor.getValue().getAuctionId()).isEqualTo(100L);
        assertThat(captor.getValue().getPayerId()).isEqualTo(88L);
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("12000.00");
        assertThat(captor.getValue().getIdempotencyKey()).isEqualTo("100-88-1");
    }

    @Test
    @DisplayName("차순위 결제 처리 중 예외가 발생하면 전파된다 (DefaultErrorHandler 재시도/DLQ)")
    void handleWinnerReassigned_exceptionPropagated() {
        WinnerReassignedEvent event = new WinnerReassignedEvent(
                "evt-r3", LocalDateTime.now(), 100L, 88L, new BigDecimal("12000.00"), "100-88-1");
        when(paymentService.process(any())).thenThrow(new RuntimeException("DB error"));

        assertThatThrownBy(() -> consumer.handleWinnerReassigned(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB error");
    }

    // ---------- 알 수 없는 이벤트 ----------

    @Test
    @DisplayName("알 수 없는 이벤트 수신 시 예외 없이 처리한다")
    void handleUnknown_doesNotThrow() {
        assertThatCode(() -> consumer.handleUnknown("unexpected")).doesNotThrowAnyException();
    }
}
