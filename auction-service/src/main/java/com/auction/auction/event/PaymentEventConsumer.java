package com.auction.auction.event;

import com.auction.auction.client.BidGrpcClient;
import com.auction.auction.client.WinnerResponse;
import com.auction.auction.client.WinningBidResponse;
import com.auction.auction.service.AuctionSettlementService;
import com.auction.common.event.PaymentCompletedEvent;
import com.auction.common.event.PaymentFailedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * payment-events 토픽을 구독해 결제 결과 이벤트를 수신한다.
 *
 * <ul>
 *   <li>PaymentCompletedEvent → 경매 COMPLETED 전이</li>
 *   <li>PaymentFailedEvent → 차순위 승계 또는 유찰 처리</li>
 * </ul>
 *
 * <p><b>D12 멱등:</b> {@link IdempotentHandler}로 eventId 기반 중복 처리를 차단한다.
 *
 * <p><b>D13 Retry/DLQ:</b> 예외를 삼키지 않고 전파하여
 * {@link org.springframework.kafka.listener.DefaultErrorHandler}가 재시도/DLQ 처리한다.
 */
@Component
@KafkaListener(topics = "payment-events", groupId = "${spring.kafka.consumer.group-id}")
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final AuctionSettlementService settlementService;
    private final BidGrpcClient bidGrpcClient;
    private final IdempotentHandler idempotentHandler;

    public PaymentEventConsumer(AuctionSettlementService settlementService,
                                BidGrpcClient bidGrpcClient,
                                IdempotentHandler idempotentHandler) {
        this.settlementService = settlementService;
        this.bidGrpcClient = bidGrpcClient;
        this.idempotentHandler = idempotentHandler;
    }

    @KafkaHandler
    public void handlePaymentCompleted(PaymentCompletedEvent event) {
        log.info("결제 완료 이벤트 수신. eventId={}, auctionId={}, paymentId={}",
                event.getEventId(), event.getAuctionId(), event.getPaymentId());

        boolean processed = idempotentHandler.processOnce(
                event.getEventId(), "PaymentCompleted",
                () -> settlementService.completeAuction(event.getAuctionId()));
        if (!processed) {
            log.info("이미 처리된 이벤트, 건너뜀. eventId={}", event.getEventId());
        }
    }

    @KafkaHandler
    public void handlePaymentFailed(PaymentFailedEvent event) {
        log.info("결제 실패 이벤트 수신. eventId={}, auctionId={}, failedPayerId={}, reason={}",
                event.getEventId(), event.getAuctionId(), event.getFailedPayerId(), event.getReason());

        if (idempotentHandler.isAlreadyProcessed(event.getEventId())) {
            log.info("이미 처리된 이벤트, 건너뜀. eventId={}", event.getEventId());
            return;
        }

        Long auctionId = event.getAuctionId();
        Long failedPayerId = event.getFailedPayerId();

        // 1. bid-service gRPC: 실패 입찰자 DISQUALIFIED + 차순위 확인 (트랜잭션 밖)
        WinnerResponse next = bidGrpcClient.reassignWinner(auctionId, failedPayerId);

        // 2. DB 쓰기: processed_event INSERT + 비즈니스 로직 (한 트랜잭션)
        if (!next.isHasBids()) {
            boolean processed = idempotentHandler.processOnce(
                    event.getEventId(), "PaymentFailed",
                    () -> settlementService.failAfterAllBiddersExhausted(auctionId));
            if (processed) {
                log.info("차순위 입찰자 없음, 유찰 처리. auctionId={}", auctionId);
            }
        } else {
            WinningBidResponse winningBid = next.getWinningBid();
            Long newWinnerId = winningBid.getBidderId();
            boolean processed = idempotentHandler.processOnce(
                    event.getEventId(), "PaymentFailed",
                    () -> {
                        int count = settlementService.reassignWinner(
                                auctionId, newWinnerId, winningBid.getAmount());
                        log.info("차순위 승계 완료. auctionId={}, newWinnerId={}, reassignmentCount={}",
                                auctionId, newWinnerId, count);
                    });
            if (!processed) {
                log.info("이미 처리된 이벤트, 건너뜀. eventId={}", event.getEventId());
            }
        }
    }

    @KafkaHandler(isDefault = true)
    public void handleUnknown(Object event) {
        log.warn("알 수 없는 이벤트 수신(payment-events). type={}", event.getClass().getName());
    }
}
