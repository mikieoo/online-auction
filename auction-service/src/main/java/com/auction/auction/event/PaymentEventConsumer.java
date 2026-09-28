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
 */
@Component
@KafkaListener(topics = "payment-events", groupId = "${spring.kafka.consumer.group-id}")
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final AuctionSettlementService settlementService;
    private final BidGrpcClient bidGrpcClient;
    private final AuctionEventProducer eventProducer;

    public PaymentEventConsumer(AuctionSettlementService settlementService,
                                BidGrpcClient bidGrpcClient,
                                AuctionEventProducer eventProducer) {
        this.settlementService = settlementService;
        this.bidGrpcClient = bidGrpcClient;
        this.eventProducer = eventProducer;
    }

    @KafkaHandler
    public void handlePaymentCompleted(PaymentCompletedEvent event) {
        log.info("결제 완료 이벤트 수신. eventId={}, auctionId={}, paymentId={}",
                event.getEventId(), event.getAuctionId(), event.getPaymentId());
        try {
            settlementService.completeAuction(event.getAuctionId());
            log.info("경매 정산 완료(COMPLETED). auctionId={}", event.getAuctionId());
        } catch (RuntimeException e) {
            log.error("경매 완료 처리 실패. auctionId={}", event.getAuctionId(), e);
        }
    }

    @KafkaHandler
    public void handlePaymentFailed(PaymentFailedEvent event) {
        log.info("결제 실패 이벤트 수신. eventId={}, auctionId={}, failedPayerId={}, reason={}",
                event.getEventId(), event.getAuctionId(), event.getFailedPayerId(), event.getReason());
        try {
            Long auctionId = event.getAuctionId();
            Long failedPayerId = event.getFailedPayerId();

            // 1. bid-service: 실패 입찰자 DISQUALIFIED + 차순위 확인
            WinnerResponse next = bidGrpcClient.reassignWinner(auctionId, failedPayerId);

            if (!next.isHasBids()) {
                // 차순위 없음 → 유찰
                settlementService.failAfterAllBiddersExhausted(auctionId);
                log.info("차순위 입찰자 없음, 유찰 처리. auctionId={}", auctionId);
                return;
            }

            // 2. 차순위 낙찰자 지정 + WinnerReassignedEvent 발행
            WinningBidResponse winningBid = next.getWinningBid();
            Long newWinnerId = winningBid.getBidderId();
            int reassignmentCount = settlementService.reassignWinner(
                    auctionId, newWinnerId, winningBid.getAmount());

            String idempotencyKey = auctionId + "-" + newWinnerId + "-" + reassignmentCount;
            eventProducer.publishReassigned(auctionId, newWinnerId, winningBid.getAmount(), idempotencyKey);
            log.info("차순위 승계 완료. auctionId={}, newWinnerId={}, reassignmentCount={}",
                    auctionId, newWinnerId, reassignmentCount);
        } catch (RuntimeException e) {
            log.error("차순위 승계 처리 실패. auctionId={}", event.getAuctionId(), e);
        }
    }

    @KafkaHandler(isDefault = true)
    public void handleUnknown(Object event) {
        log.warn("알 수 없는 이벤트 수신(payment-events). type={}", event.getClass().getName());
    }
}
