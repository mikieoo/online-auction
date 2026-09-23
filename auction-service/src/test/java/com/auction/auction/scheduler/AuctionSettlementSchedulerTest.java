package com.auction.auction.scheduler;

import com.auction.auction.client.BidGrpcClient;
import com.auction.auction.client.PaymentClient;
import com.auction.auction.client.PaymentRequest;
import com.auction.auction.client.PaymentResponse;
import com.auction.auction.client.PaymentStatus;
import com.auction.auction.client.WinnerResponse;
import com.auction.auction.client.WinningBidResponse;
import com.auction.auction.dto.SettlementTarget;
import com.auction.auction.event.AuctionEventProducer;
import com.auction.auction.service.AuctionSettlementService;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuctionSettlementSchedulerTest {

    private static final int BATCH_SIZE = 50;
    private static final Long AUCTION_ID = 1L;
    private static final Long OTHER_AUCTION_ID = 2L;
    private static final Long BIDDER_ID = 77L;
    private static final BigDecimal AMOUNT = new BigDecimal("15000.00");

    @Mock
    private AuctionSettlementService settlementService;

    @Mock
    private BidGrpcClient bidGrpcClient;

    @Mock
    private PaymentClient paymentClient;

    @Mock
    private AuctionEventProducer eventProducer;

    private AuctionSettlementScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new AuctionSettlementScheduler(settlementService, bidGrpcClient, paymentClient, eventProducer, BATCH_SIZE);
    }

    private static WinnerResponse winner(Long auctionId, Long bidderId, BigDecimal amount) {
        WinningBidResponse bid = new WinningBidResponse(500L, auctionId, bidderId, amount, "WINNER",
                LocalDateTime.of(2026, 9, 16, 10, 0));
        return new WinnerResponse(auctionId, true, bid);
    }

    private static WinnerResponse noBids(Long auctionId) {
        return new WinnerResponse(auctionId, false, null);
    }

    private static PaymentResponse payment(Long auctionId, PaymentStatus status) {
        return new PaymentResponse(900L, auctionId, BIDDER_ID, AMOUNT, "key", status, null,
                LocalDateTime.of(2026, 9, 16, 10, 0), LocalDateTime.of(2026, 9, 16, 10, 0));
    }

    private static SettlementTarget target(Long auctionId, int reassignmentCount) {
        return new SettlementTarget(auctionId, reassignmentCount);
    }

    // ---------- A단계: 마감 ----------

    @Nested
    @DisplayName("A단계 마감")
    class CloseStep {

        @Test
        @DisplayName("end_time이 지난 ACTIVE 경매 id마다 closeAuction을 호출한다")
        void closesEachEndedAuction() {
            when(settlementService.findEndedActiveAuctionIds()).thenReturn(List.of(AUCTION_ID, OTHER_AUCTION_ID));
            when(settlementService.findSettlementTargets(BATCH_SIZE)).thenReturn(List.of());

            scheduler.settle();

            verify(settlementService).closeAuction(AUCTION_ID);
            verify(settlementService).closeAuction(OTHER_AUCTION_ID);
            verifyNoInteractions(bidGrpcClient, paymentClient);
        }

        @Test
        @DisplayName("한 경매 마감 예외는 다음 경매 마감을 막지 않는다")
        void exceptionInOneCloseDoesNotStopOthers() {
            when(settlementService.findEndedActiveAuctionIds()).thenReturn(List.of(AUCTION_ID, OTHER_AUCTION_ID));
            when(settlementService.findSettlementTargets(BATCH_SIZE)).thenReturn(List.of());
            doThrow(new IllegalStateException("경매를 마감할 수 없습니다.")).when(settlementService).closeAuction(AUCTION_ID);

            scheduler.settle();

            verify(settlementService).closeAuction(OTHER_AUCTION_ID);
        }

        @Test
        @DisplayName("마감 성공 시 AuctionClosedEvent를 발행한다")
        void closePublishesEvent() {
            when(settlementService.findEndedActiveAuctionIds()).thenReturn(List.of(AUCTION_ID));
            when(settlementService.findSettlementTargets(BATCH_SIZE)).thenReturn(List.of());

            scheduler.settle();

            verify(eventProducer).publishClosed(AUCTION_ID);
        }

        @Test
        @DisplayName("마감 실패 시 이벤트를 발행하지 않는다")
        void closeFailureDoesNotPublishEvent() {
            when(settlementService.findEndedActiveAuctionIds()).thenReturn(List.of(AUCTION_ID));
            when(settlementService.findSettlementTargets(BATCH_SIZE)).thenReturn(List.of());
            doThrow(new IllegalStateException("마감 실패")).when(settlementService).closeAuction(AUCTION_ID);

            scheduler.settle();

            verify(eventProducer, never()).publishClosed(AUCTION_ID);
        }

        @Test
        @DisplayName("A단계는 B단계보다 먼저 실행되고 B단계 실패와 무관하게 완료된다")
        void closeStepRunsBeforeAndIndependentlyOfSettlement() {
            when(settlementService.findEndedActiveAuctionIds()).thenReturn(List.of(AUCTION_ID));
            when(settlementService.findSettlementTargets(BATCH_SIZE)).thenReturn(List.of(target(OTHER_AUCTION_ID, 0)));
            when(bidGrpcClient.confirmWinner(OTHER_AUCTION_ID))
                    .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

            scheduler.settle();

            InOrder inOrder = inOrder(settlementService, bidGrpcClient);
            inOrder.verify(settlementService).closeAuction(AUCTION_ID);
            inOrder.verify(bidGrpcClient).confirmWinner(OTHER_AUCTION_ID);
        }
    }

    // ---------- B단계: 정산 ----------

    @Nested
    @DisplayName("B단계 정산")
    class SettlementStep {

        @BeforeEach
        void noAuctionsToClose() {
            when(settlementService.findEndedActiveAuctionIds()).thenReturn(List.of());
        }

        @Test
        @DisplayName("hasBids=false → markNoBids(유찰 FAILED), 결제 호출 없음")
        void noBidsMarksFailed() {
            when(settlementService.findSettlementTargets(BATCH_SIZE)).thenReturn(List.of(target(AUCTION_ID, 0)));
            when(bidGrpcClient.confirmWinner(AUCTION_ID)).thenReturn(noBids(AUCTION_ID));

            scheduler.settle();

            verify(settlementService).markNoBids(AUCTION_ID);
            verifyNoInteractions(paymentClient);
            verify(settlementService, never()).completeWithWinner(anyLong(), anyLong(), any());
            verify(settlementService, never()).recordPaymentFailed(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("결제 COMPLETED → completeWithWinner(winner + COMPLETED)")
        void paymentCompletedCompletesAuction() {
            when(settlementService.findSettlementTargets(BATCH_SIZE)).thenReturn(List.of(target(AUCTION_ID, 0)));
            when(bidGrpcClient.confirmWinner(AUCTION_ID)).thenReturn(winner(AUCTION_ID, BIDDER_ID, AMOUNT));
            when(paymentClient.requestPayment(any(PaymentRequest.class)))
                    .thenReturn(payment(AUCTION_ID, PaymentStatus.COMPLETED));

            scheduler.settle();

            verify(settlementService).completeWithWinner(AUCTION_ID, BIDDER_ID, AMOUNT);
            verify(settlementService, never()).recordPaymentFailed(anyLong(), anyLong(), any());
            verify(settlementService, never()).markNoBids(anyLong());
        }

        @Test
        @DisplayName("결제 FAILED → recordPaymentFailed(winner 저장, CLOSED 유지)")
        void paymentFailedRecordsWinnerOnly() {
            when(settlementService.findSettlementTargets(BATCH_SIZE)).thenReturn(List.of(target(AUCTION_ID, 0)));
            when(bidGrpcClient.confirmWinner(AUCTION_ID)).thenReturn(winner(AUCTION_ID, BIDDER_ID, AMOUNT));
            when(paymentClient.requestPayment(any(PaymentRequest.class)))
                    .thenReturn(payment(AUCTION_ID, PaymentStatus.FAILED));

            scheduler.settle();

            verify(settlementService).recordPaymentFailed(AUCTION_ID, BIDDER_ID, AMOUNT);
            verify(settlementService, never()).completeWithWinner(anyLong(), anyLong(), any());
            verify(settlementService, never()).markNoBids(anyLong());
        }

        @Test
        @DisplayName("결제 REQUESTED → 저장하지 않고 다음 주기에 재시도")
        void paymentRequestedSavesNothing() {
            when(settlementService.findSettlementTargets(BATCH_SIZE)).thenReturn(List.of(target(AUCTION_ID, 0)));
            when(bidGrpcClient.confirmWinner(AUCTION_ID)).thenReturn(winner(AUCTION_ID, BIDDER_ID, AMOUNT));
            when(paymentClient.requestPayment(any(PaymentRequest.class)))
                    .thenReturn(payment(AUCTION_ID, PaymentStatus.REQUESTED));

            scheduler.settle();

            verifyNoWrites();
        }

        @Test
        @DisplayName("bid-service gRPC 호출 실패(UNAVAILABLE) → 저장 없음, 결제 호출 없음")
        void bidClientFailureSavesNothing() {
            when(settlementService.findSettlementTargets(BATCH_SIZE)).thenReturn(List.of(target(AUCTION_ID, 0)));
            when(bidGrpcClient.confirmWinner(AUCTION_ID))
                    .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

            scheduler.settle();

            verifyNoWrites();
            verifyNoInteractions(paymentClient);
        }

        @Test
        @DisplayName("bid-service gRPC 데드라인 초과(DEADLINE_EXCEEDED) → 저장 없음")
        void bidClientTimeoutSavesNothing() {
            when(settlementService.findSettlementTargets(BATCH_SIZE)).thenReturn(List.of(target(AUCTION_ID, 0)));
            when(bidGrpcClient.confirmWinner(AUCTION_ID))
                    .thenThrow(new StatusRuntimeException(Status.DEADLINE_EXCEEDED));

            scheduler.settle();

            verifyNoWrites();
            verifyNoInteractions(paymentClient);
        }

        @Test
        @DisplayName("payment-service 호출 실패(FeignException) → 저장 없음")
        void paymentClientFailureSavesNothing() {
            when(settlementService.findSettlementTargets(BATCH_SIZE)).thenReturn(List.of(target(AUCTION_ID, 0)));
            when(bidGrpcClient.confirmWinner(AUCTION_ID)).thenReturn(winner(AUCTION_ID, BIDDER_ID, AMOUNT));
            when(paymentClient.requestPayment(any(PaymentRequest.class)))
                    .thenThrow(FeignException.errorStatus("PaymentClient#requestPayment",
                            feign.Response.builder().status(500).request(
                                    feign.Request.create(feign.Request.HttpMethod.POST, "http://payment",
                                            java.util.Collections.emptyMap(), null, null, null))
                                    .build()));

            scheduler.settle();

            verifyNoWrites();
        }

        @Test
        @DisplayName("첫 경매에서 예외가 나도 두 번째 경매는 정상 처리된다")
        void exceptionInFirstAuctionDoesNotBlockSecond() {
            when(settlementService.findSettlementTargets(BATCH_SIZE))
                    .thenReturn(List.of(target(AUCTION_ID, 0), target(OTHER_AUCTION_ID, 0)));
            when(bidGrpcClient.confirmWinner(AUCTION_ID)).thenThrow(new RuntimeException("예상치 못한 오류"));
            when(bidGrpcClient.confirmWinner(OTHER_AUCTION_ID)).thenReturn(noBids(OTHER_AUCTION_ID));

            scheduler.settle();

            verify(settlementService).markNoBids(OTHER_AUCTION_ID);
            verify(settlementService, never()).markNoBids(AUCTION_ID);
        }

        @Test
        @DisplayName("DB 저장 단계 예외(예: 상태 전이 불가)도 다음 경매 처리를 막지 않는다")
        void persistenceExceptionDoesNotBlockNext() {
            when(settlementService.findSettlementTargets(BATCH_SIZE))
                    .thenReturn(List.of(target(AUCTION_ID, 0), target(OTHER_AUCTION_ID, 0)));
            when(bidGrpcClient.confirmWinner(AUCTION_ID)).thenReturn(noBids(AUCTION_ID));
            doThrow(new IllegalStateException("경매를 실패 처리할 수 없습니다.")).when(settlementService).markNoBids(AUCTION_ID);
            when(bidGrpcClient.confirmWinner(OTHER_AUCTION_ID)).thenReturn(noBids(OTHER_AUCTION_ID));

            scheduler.settle();

            verify(settlementService).markNoBids(OTHER_AUCTION_ID);
        }

        @Test
        @DisplayName("결제 요청 body: auctionId, payerId=낙찰자, amount=낙찰가, idempotencyKey={auctionId}-{winnerId}-{reassignmentCount}")
        void paymentRequestCarriesIdempotencyKey() {
            when(settlementService.findSettlementTargets(BATCH_SIZE)).thenReturn(List.of(target(AUCTION_ID, 2)));
            when(bidGrpcClient.confirmWinner(AUCTION_ID)).thenReturn(winner(AUCTION_ID, BIDDER_ID, AMOUNT));
            when(paymentClient.requestPayment(any(PaymentRequest.class)))
                    .thenReturn(payment(AUCTION_ID, PaymentStatus.COMPLETED));

            scheduler.settle();

            ArgumentCaptor<PaymentRequest> captor = ArgumentCaptor.forClass(PaymentRequest.class);
            verify(paymentClient).requestPayment(captor.capture());
            PaymentRequest sent = captor.getValue();
            assertThat(sent.getAuctionId()).isEqualTo(AUCTION_ID);
            assertThat(sent.getPayerId()).isEqualTo(BIDDER_ID);
            assertThat(sent.getAmount()).isEqualByComparingTo(AMOUNT);
            assertThat(sent.getIdempotencyKey()).isEqualTo("1-77-2");
        }

        @Test
        @DisplayName("hasBids=true인데 winningBid가 null이면 저장하지 않는다")
        void inconsistentWinnerResponseSavesNothing() {
            when(settlementService.findSettlementTargets(BATCH_SIZE)).thenReturn(List.of(target(AUCTION_ID, 0)));
            when(bidGrpcClient.confirmWinner(AUCTION_ID)).thenReturn(new WinnerResponse(AUCTION_ID, true, null));

            scheduler.settle();

            verifyNoWrites();
            verifyNoInteractions(paymentClient);
        }

        @Test
        @DisplayName("틱당 상한(settlement-batch-size)을 조회에 전달한다")
        void passesBatchSizeToQuery() {
            when(settlementService.findSettlementTargets(anyInt())).thenReturn(List.of());

            scheduler.settle();

            verify(settlementService).findSettlementTargets(eq(BATCH_SIZE));
        }

        private void verifyNoWrites() {
            verify(settlementService, never()).markNoBids(anyLong());
            verify(settlementService, never()).completeWithWinner(anyLong(), anyLong(), any());
            verify(settlementService, never()).recordPaymentFailed(anyLong(), anyLong(), any());
            verify(settlementService, never()).closeAuction(anyLong());
        }
    }

    // ---------- B단계: 서킷 브레이커 ----------

    @Nested
    @DisplayName("B단계 정산 — 서킷 브레이커 예외")
    class CircuitBreakerStep {

        private CallNotPermittedException breakerOpen() {
            return CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("bid-service"));
        }

        @Test
        @DisplayName("bid gRPC 클라이언트가 StatusRuntimeException(UNAVAILABLE) → 저장 없음, 다음 경매는 계속 처리")
        void bidGrpcUnavailableSavesNothingAndContinues() {
            when(settlementService.findEndedActiveAuctionIds()).thenReturn(List.of());
            when(settlementService.findSettlementTargets(BATCH_SIZE))
                    .thenReturn(List.of(target(AUCTION_ID, 0), target(OTHER_AUCTION_ID, 0)));
            when(bidGrpcClient.confirmWinner(AUCTION_ID))
                    .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
            when(bidGrpcClient.confirmWinner(OTHER_AUCTION_ID)).thenReturn(noBids(OTHER_AUCTION_ID));

            scheduler.settle();

            verify(settlementService, never()).markNoBids(AUCTION_ID);
            verify(settlementService).markNoBids(OTHER_AUCTION_ID);
            verify(settlementService, never()).completeWithWinner(anyLong(), anyLong(), any());
            verify(settlementService, never()).recordPaymentFailed(anyLong(), anyLong(), any());
            verifyNoInteractions(paymentClient);
        }

        @Test
        @DisplayName("payment 클라이언트가 FeignException → 저장 없음, 다음 경매는 계속 처리")
        void paymentFeignExceptionSavesNothingAndContinues() {
            when(settlementService.findEndedActiveAuctionIds()).thenReturn(List.of());
            when(settlementService.findSettlementTargets(BATCH_SIZE))
                    .thenReturn(List.of(target(AUCTION_ID, 0), target(OTHER_AUCTION_ID, 0)));
            when(bidGrpcClient.confirmWinner(AUCTION_ID)).thenReturn(winner(AUCTION_ID, BIDDER_ID, AMOUNT));
            when(paymentClient.requestPayment(any(PaymentRequest.class)))
                    .thenThrow(FeignException.errorStatus("PaymentClient#requestPayment",
                            feign.Response.builder().status(500).request(
                                    feign.Request.create(feign.Request.HttpMethod.POST, "http://payment",
                                            java.util.Collections.emptyMap(), null, null, null))
                                    .build()));
            when(bidGrpcClient.confirmWinner(OTHER_AUCTION_ID)).thenReturn(noBids(OTHER_AUCTION_ID));

            scheduler.settle();

            verify(settlementService).markNoBids(OTHER_AUCTION_ID);
            verify(settlementService, never()).completeWithWinner(anyLong(), anyLong(), any());
            verify(settlementService, never()).recordPaymentFailed(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("첫 대상에서 CallNotPermittedException(브레이커 OPEN) → 남은 대상은 시도조차 하지 않고 저장도 없다")
        void breakerOpenStopsRemainingTargets() {
            when(settlementService.findEndedActiveAuctionIds()).thenReturn(List.of());
            when(settlementService.findSettlementTargets(BATCH_SIZE))
                    .thenReturn(List.of(target(AUCTION_ID, 0), target(OTHER_AUCTION_ID, 0), target(3L, 0)));
            when(bidGrpcClient.confirmWinner(AUCTION_ID)).thenThrow(breakerOpen());

            scheduler.settle();

            verify(bidGrpcClient).confirmWinner(AUCTION_ID);
            verify(bidGrpcClient, never()).confirmWinner(OTHER_AUCTION_ID);
            verify(bidGrpcClient, never()).confirmWinner(3L);
            verifyNoInteractions(paymentClient);
            verifyNoSettlementWrites();
        }

        @Test
        @DisplayName("RuntimeException에 싸인 CallNotPermittedException도 OPEN으로 판정해 남은 대상을 건너뛴다")
        void wrappedBreakerOpenStopsRemainingTargets() {
            when(settlementService.findEndedActiveAuctionIds()).thenReturn(List.of());
            when(settlementService.findSettlementTargets(BATCH_SIZE))
                    .thenReturn(List.of(target(AUCTION_ID, 0), target(OTHER_AUCTION_ID, 0)));
            when(bidGrpcClient.confirmWinner(AUCTION_ID))
                    .thenThrow(new RuntimeException("wrapper", breakerOpen()));

            scheduler.settle();

            verify(bidGrpcClient).confirmWinner(AUCTION_ID);
            verify(bidGrpcClient, never()).confirmWinner(OTHER_AUCTION_ID);
            verifyNoInteractions(paymentClient);
            verifyNoSettlementWrites();
        }

        @Test
        @DisplayName("payment 브레이커 OPEN이어도 남은 대상을 건너뛴다(앞서 성공한 bid 응답은 저장하지 않는다)")
        void paymentBreakerOpenStopsRemainingTargets() {
            when(settlementService.findEndedActiveAuctionIds()).thenReturn(List.of());
            when(settlementService.findSettlementTargets(BATCH_SIZE))
                    .thenReturn(List.of(target(AUCTION_ID, 0), target(OTHER_AUCTION_ID, 0)));
            when(bidGrpcClient.confirmWinner(AUCTION_ID)).thenReturn(winner(AUCTION_ID, BIDDER_ID, AMOUNT));
            when(paymentClient.requestPayment(any(PaymentRequest.class))).thenThrow(breakerOpen());

            scheduler.settle();

            verify(bidGrpcClient, never()).confirmWinner(OTHER_AUCTION_ID);
            verifyNoSettlementWrites();
        }

        @Test
        @DisplayName("브레이커 OPEN으로 B단계가 중단돼도 A단계(마감)는 먼저 실행되어 전부 완료된다")
        void closeStepCompletesBeforeBreakerOpenStop() {
            when(settlementService.findEndedActiveAuctionIds()).thenReturn(List.of(10L, 11L));
            when(settlementService.findSettlementTargets(BATCH_SIZE))
                    .thenReturn(List.of(target(AUCTION_ID, 0), target(OTHER_AUCTION_ID, 0)));
            when(bidGrpcClient.confirmWinner(AUCTION_ID)).thenThrow(breakerOpen());

            scheduler.settle();

            InOrder inOrder = inOrder(settlementService, bidGrpcClient);
            inOrder.verify(settlementService).closeAuction(10L);
            inOrder.verify(settlementService).closeAuction(11L);
            inOrder.verify(bidGrpcClient).confirmWinner(AUCTION_ID);
            verify(bidGrpcClient, never()).confirmWinner(OTHER_AUCTION_ID);
            verifyNoSettlementWrites();
        }

        private void verifyNoSettlementWrites() {
            verify(settlementService, never()).markNoBids(anyLong());
            verify(settlementService, never()).completeWithWinner(anyLong(), anyLong(), any());
            verify(settlementService, never()).recordPaymentFailed(anyLong(), anyLong(), any());
        }
    }
}
