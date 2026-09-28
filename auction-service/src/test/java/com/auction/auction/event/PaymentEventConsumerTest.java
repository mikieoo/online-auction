package com.auction.auction.event;

import com.auction.auction.client.BidGrpcClient;
import com.auction.auction.client.WinnerResponse;
import com.auction.auction.client.WinningBidResponse;
import com.auction.auction.service.AuctionSettlementService;
import com.auction.common.event.PaymentCompletedEvent;
import com.auction.common.event.PaymentFailedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentEventConsumerTest {

    @Mock
    private AuctionSettlementService settlementService;

    @Mock
    private BidGrpcClient bidGrpcClient;

    @InjectMocks
    private PaymentEventConsumer consumer;

    // ---------- PaymentCompleted ----------

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

    // ---------- PaymentFailed — 차순위 승계 ----------

    @Test
    @DisplayName("결제 실패 시 차순위 입찰자가 있으면 재지정한다 (outbox는 서비스 내부에서 기록)")
    void handlePaymentFailed_reassignsToNextBidder() {
        PaymentFailedEvent event = new PaymentFailedEvent(
                "evt-3", LocalDateTime.now(), 100L, 900L, 77L, "SIMULATED_FAILURE");

        WinningBidResponse nextBid = new WinningBidResponse(
                501L, 100L, 88L, new BigDecimal("12000.00"), "WINNER",
                LocalDateTime.of(2026, 9, 16, 10, 0));
        when(bidGrpcClient.reassignWinner(100L, 77L))
                .thenReturn(new WinnerResponse(100L, true, nextBid));
        when(settlementService.reassignWinner(100L, 88L, new BigDecimal("12000.00")))
                .thenReturn(1);

        consumer.handlePaymentFailed(event);

        verify(settlementService).reassignWinner(100L, 88L, new BigDecimal("12000.00"));
    }

    @Test
    @DisplayName("결제 실패 시 차순위가 없으면 유찰 처리한다")
    void handlePaymentFailed_noBiddersLeft_fails() {
        PaymentFailedEvent event = new PaymentFailedEvent(
                "evt-4", LocalDateTime.now(), 100L, 900L, 77L, "SIMULATED_FAILURE");

        when(bidGrpcClient.reassignWinner(100L, 77L))
                .thenReturn(new WinnerResponse(100L, false, null));

        consumer.handlePaymentFailed(event);

        verify(settlementService).failAfterAllBiddersExhausted(100L);
    }

    @Test
    @DisplayName("차순위 승계 중 예외가 발생해도 전파되지 않는다")
    void handlePaymentFailed_exceptionSwallowed() {
        PaymentFailedEvent event = new PaymentFailedEvent(
                "evt-5", LocalDateTime.now(), 100L, 900L, 77L, "SIMULATED_FAILURE");

        when(bidGrpcClient.reassignWinner(100L, 77L))
                .thenThrow(new RuntimeException("gRPC error"));

        assertThatCode(() -> consumer.handlePaymentFailed(event)).doesNotThrowAnyException();
        verify(settlementService, never()).reassignWinner(anyLong(), anyLong(), any());
        verify(settlementService, never()).failAfterAllBiddersExhausted(anyLong());
    }

    // ---------- 알 수 없는 이벤트 ----------

    @Test
    @DisplayName("알 수 없는 이벤트 수신 시 예외 없이 처리한다")
    void handleUnknown_doesNotThrow() {
        assertThatCode(() -> consumer.handleUnknown("unexpected")).doesNotThrowAnyException();
    }
}
