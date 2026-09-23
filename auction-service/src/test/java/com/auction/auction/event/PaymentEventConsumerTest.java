package com.auction.auction.event;

import com.auction.auction.service.AuctionSettlementService;
import com.auction.common.event.PaymentCompletedEvent;
import com.auction.common.event.PaymentFailedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class PaymentEventConsumerTest {

    @Mock
    private AuctionSettlementService settlementService;

    @InjectMocks
    private PaymentEventConsumer consumer;

    @Test
    @DisplayName("PaymentCompletedEvent 수신 시 경매를 COMPLETED로 전이한다")
    void handlePaymentCompleted_completesAuction() {
        PaymentCompletedEvent event = new PaymentCompletedEvent("evt-1", LocalDateTime.now(), 100L, 900L);

        consumer.handlePaymentCompleted(event);

        verify(settlementService).completeAuction(100L);
    }

    @Test
    @DisplayName("PaymentCompletedEvent 처리 중 예외가 발생해도 전파되지 않는다")
    void handlePaymentCompleted_exceptionSwallowed() {
        PaymentCompletedEvent event = new PaymentCompletedEvent("evt-2", LocalDateTime.now(), 100L, 900L);
        doThrow(new RuntimeException("DB error")).when(settlementService).completeAuction(100L);

        assertThatCode(() -> consumer.handlePaymentCompleted(event)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("PaymentFailedEvent 수신 시 예외 없이 처리한다 (D10 차순위 승계 전까지 로그만)")
    void handlePaymentFailed_doesNotThrow() {
        PaymentFailedEvent event = new PaymentFailedEvent(
                "evt-3", LocalDateTime.now(), 100L, 900L, 77L, "SIMULATED_FAILURE");

        assertThatCode(() -> consumer.handlePaymentFailed(event)).doesNotThrowAnyException();
        verify(settlementService, never()).completeAuction(100L);
    }

    @Test
    @DisplayName("알 수 없는 이벤트 수신 시 예외 없이 처리한다")
    void handleUnknown_doesNotThrow() {
        assertThatCode(() -> consumer.handleUnknown("unexpected")).doesNotThrowAnyException();
    }
}
