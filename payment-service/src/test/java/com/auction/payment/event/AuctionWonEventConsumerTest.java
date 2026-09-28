package com.auction.payment.event;

import com.auction.common.event.AuctionWonEvent;
import com.auction.common.event.WinnerReassignedEvent;
import com.auction.payment.domain.Payment;
import com.auction.payment.domain.PaymentStatus;
import com.auction.payment.dto.PaymentProcessResult;
import com.auction.payment.service.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuctionWonEventConsumerTest {

    @Mock
    private PaymentService paymentService;

    @Mock
    private PaymentEventProducer eventProducer;

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

    private Payment failedPayment() {
        Payment payment = new Payment(100L, 77L, new BigDecimal("15000.00"), "100-77-0");
        ReflectionTestUtils.setField(payment, "paymentId", 900L);
        payment.fail("SIMULATED_FAILURE");
        return payment;
    }

    private Payment requestedPayment() {
        Payment payment = new Payment(100L, 77L, new BigDecimal("15000.00"), "100-77-0");
        ReflectionTestUtils.setField(payment, "paymentId", 900L);
        return payment;
    }

    @Test
    @DisplayName("결제 완료 시 PaymentCompletedEvent를 발행한다")
    void handleAuctionWon_completed_publishesCompletedEvent() {
        Payment payment = completedPayment();
        when(paymentService.process(any())).thenReturn(PaymentProcessResult.created(payment));

        consumer.handleAuctionWon(sampleEvent());

        verify(eventProducer).publishCompleted(payment);
        verify(eventProducer, never()).publishFailed(any());
    }

    @Test
    @DisplayName("결제 실패 시 PaymentFailedEvent를 발행한다")
    void handleAuctionWon_failed_publishesFailedEvent() {
        Payment payment = failedPayment();
        when(paymentService.process(any())).thenReturn(PaymentProcessResult.created(payment));

        consumer.handleAuctionWon(sampleEvent());

        verify(eventProducer).publishFailed(payment);
        verify(eventProducer, never()).publishCompleted(any());
    }

    @Test
    @DisplayName("결제가 REQUESTED 상태로 남으면 이벤트를 발행하지 않는다")
    void handleAuctionWon_requested_publishesNothing() {
        Payment payment = requestedPayment();
        when(paymentService.process(any())).thenReturn(PaymentProcessResult.created(payment));

        consumer.handleAuctionWon(sampleEvent());

        verify(eventProducer, never()).publishCompleted(any());
        verify(eventProducer, never()).publishFailed(any());
    }

    @Test
    @DisplayName("PaymentService 예외 발생 시 전파되지 않는다")
    void handleAuctionWon_exceptionSwallowed() {
        when(paymentService.process(any())).thenThrow(new RuntimeException("DB error"));

        assertThatCode(() -> consumer.handleAuctionWon(sampleEvent())).doesNotThrowAnyException();
        verify(eventProducer, never()).publishCompleted(any());
        verify(eventProducer, never()).publishFailed(any());
    }

    // ---------- WinnerReassignedEvent ----------

    @Test
    @DisplayName("차순위 승계 이벤트 수신 시 결제를 처리하고 결과 이벤트를 발행한다")
    void handleWinnerReassigned_completed_publishesCompletedEvent() {
        WinnerReassignedEvent event = new WinnerReassignedEvent(
                "evt-r1", LocalDateTime.now(), 100L, 88L, new BigDecimal("12000.00"), "100-88-1");
        Payment payment = new Payment(100L, 88L, new BigDecimal("12000.00"), "100-88-1");
        ReflectionTestUtils.setField(payment, "paymentId", 901L);
        payment.complete();

        when(paymentService.process(any())).thenReturn(PaymentProcessResult.created(payment));

        consumer.handleWinnerReassigned(event);

        verify(eventProducer).publishCompleted(payment);
        verify(eventProducer, never()).publishFailed(any());
    }

    @Test
    @DisplayName("차순위 결제 실패 시 PaymentFailedEvent를 발행한다")
    void handleWinnerReassigned_failed_publishesFailedEvent() {
        WinnerReassignedEvent event = new WinnerReassignedEvent(
                "evt-r2", LocalDateTime.now(), 100L, 88L, new BigDecimal("12000.00"), "100-88-1");
        Payment payment = new Payment(100L, 88L, new BigDecimal("12000.00"), "100-88-1");
        ReflectionTestUtils.setField(payment, "paymentId", 901L);
        payment.fail("SIMULATED_FAILURE");

        when(paymentService.process(any())).thenReturn(PaymentProcessResult.created(payment));

        consumer.handleWinnerReassigned(event);

        verify(eventProducer).publishFailed(payment);
        verify(eventProducer, never()).publishCompleted(any());
    }

    @Test
    @DisplayName("차순위 결제 처리 중 예외가 발생해도 전파되지 않는다")
    void handleWinnerReassigned_exceptionSwallowed() {
        WinnerReassignedEvent event = new WinnerReassignedEvent(
                "evt-r3", LocalDateTime.now(), 100L, 88L, new BigDecimal("12000.00"), "100-88-1");
        when(paymentService.process(any())).thenThrow(new RuntimeException("DB error"));

        assertThatCode(() -> consumer.handleWinnerReassigned(event)).doesNotThrowAnyException();
    }

    // ---------- 알 수 없는 이벤트 ----------

    @Test
    @DisplayName("알 수 없는 이벤트 수신 시 예외 없이 처리한다")
    void handleUnknown_doesNotThrow() {
        assertThatCode(() -> consumer.handleUnknown("unexpected")).doesNotThrowAnyException();
    }
}
