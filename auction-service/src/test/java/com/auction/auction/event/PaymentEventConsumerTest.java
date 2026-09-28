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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentEventConsumerTest {

    @Mock
    private AuctionSettlementService settlementService;

    @Mock
    private BidGrpcClient bidGrpcClient;

    @Mock
    private IdempotentHandler idempotentHandler;

    @InjectMocks
    private PaymentEventConsumer consumer;

    /** processOnce가 Runnable을 실행하고 true를 반환하도록 설정 */
    private void stubProcessOnceExecutes() {
        when(idempotentHandler.processOnce(anyString(), anyString(), any(Runnable.class)))
                .thenAnswer(inv -> {
                    inv.getArgument(2, Runnable.class).run();
                    return true;
                });
    }

    // ---------- PaymentCompleted ----------

    @Test
    @DisplayName("PaymentCompletedEvent 수신 시 경매를 COMPLETED로 전이한다")
    void handlePaymentCompleted_completesAuction() {
        stubProcessOnceExecutes();
        PaymentCompletedEvent event = new PaymentCompletedEvent("evt-1", LocalDateTime.now(), 100L, 900L);

        consumer.handlePaymentCompleted(event);

        verify(idempotentHandler).processOnce(eq("evt-1"), eq("PaymentCompleted"), any());
        verify(settlementService).completeAuction(100L);
    }

    @Test
    @DisplayName("이미 처리된 PaymentCompletedEvent는 건너뛴다")
    void handlePaymentCompleted_duplicateSkipped() {
        when(idempotentHandler.processOnce(anyString(), anyString(), any(Runnable.class)))
                .thenReturn(false);
        PaymentCompletedEvent event = new PaymentCompletedEvent("evt-dup", LocalDateTime.now(), 100L, 900L);

        consumer.handlePaymentCompleted(event);

        verify(settlementService, never()).completeAuction(anyLong());
    }

    @Test
    @DisplayName("PaymentCompletedEvent 처리 중 예외가 발생해도 전파되지 않는다")
    void handlePaymentCompleted_exceptionSwallowed() {
        when(idempotentHandler.processOnce(anyString(), anyString(), any(Runnable.class)))
                .thenThrow(new RuntimeException("DB error"));
        PaymentCompletedEvent event = new PaymentCompletedEvent("evt-2", LocalDateTime.now(), 100L, 900L);

        assertThatCode(() -> consumer.handlePaymentCompleted(event)).doesNotThrowAnyException();
    }

    // ---------- PaymentFailed — 차순위 승계 ----------

    @Test
    @DisplayName("결제 실패 시 차순위 입찰자가 있으면 재지정한다")
    void handlePaymentFailed_reassignsToNextBidder() {
        when(idempotentHandler.isAlreadyProcessed("evt-3")).thenReturn(false);
        stubProcessOnceExecutes();

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
        when(idempotentHandler.isAlreadyProcessed("evt-4")).thenReturn(false);
        stubProcessOnceExecutes();

        PaymentFailedEvent event = new PaymentFailedEvent(
                "evt-4", LocalDateTime.now(), 100L, 900L, 77L, "SIMULATED_FAILURE");
        when(bidGrpcClient.reassignWinner(100L, 77L))
                .thenReturn(new WinnerResponse(100L, false, null));

        consumer.handlePaymentFailed(event);

        verify(settlementService).failAfterAllBiddersExhausted(100L);
    }

    @Test
    @DisplayName("이미 처리된 PaymentFailedEvent는 gRPC 호출 없이 건너뛴다")
    void handlePaymentFailed_duplicateSkipped() {
        when(idempotentHandler.isAlreadyProcessed("evt-dup")).thenReturn(true);

        PaymentFailedEvent event = new PaymentFailedEvent(
                "evt-dup", LocalDateTime.now(), 100L, 900L, 77L, "SIMULATED_FAILURE");

        consumer.handlePaymentFailed(event);

        verifyNoInteractions(bidGrpcClient);
        verify(settlementService, never()).reassignWinner(anyLong(), anyLong(), any());
        verify(settlementService, never()).failAfterAllBiddersExhausted(anyLong());
    }

    @Test
    @DisplayName("사전 체크 통과 후 processOnce에서 중복 감지되면 비즈니스 로직을 실행하지 않는다")
    void handlePaymentFailed_concurrentDuplicateDetectedAtProcessOnce() {
        when(idempotentHandler.isAlreadyProcessed("evt-race")).thenReturn(false);
        when(idempotentHandler.processOnce(anyString(), anyString(), any(Runnable.class)))
                .thenReturn(false);

        PaymentFailedEvent event = new PaymentFailedEvent(
                "evt-race", LocalDateTime.now(), 100L, 900L, 77L, "SIMULATED_FAILURE");
        when(bidGrpcClient.reassignWinner(100L, 77L))
                .thenReturn(new WinnerResponse(100L, false, null));

        consumer.handlePaymentFailed(event);

        // gRPC는 호출됨 (사전 체크 통과 후이므로), 하지만 비즈니스 DB 쓰기는 안 됨
        verify(bidGrpcClient).reassignWinner(100L, 77L);
        verify(settlementService, never()).failAfterAllBiddersExhausted(anyLong());
    }

    @Test
    @DisplayName("차순위 승계 중 예외가 발생해도 전파되지 않는다")
    void handlePaymentFailed_exceptionSwallowed() {
        when(idempotentHandler.isAlreadyProcessed("evt-5")).thenReturn(false);

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
