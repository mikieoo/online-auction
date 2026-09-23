package com.auction.auction.event;

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
 *   <li>PaymentFailedEvent → 로그만 남김 (D10에서 차순위 승계 구현)</li>
 * </ul>
 */
@Component
@KafkaListener(topics = "payment-events", groupId = "${spring.kafka.consumer.group-id}")
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final AuctionSettlementService settlementService;

    public PaymentEventConsumer(AuctionSettlementService settlementService) {
        this.settlementService = settlementService;
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
        log.info("결제 실패 이벤트 수신. eventId={}, auctionId={}, paymentId={}, reason={}",
                event.getEventId(), event.getAuctionId(), event.getPaymentId(), event.getReason());
        // D10에서 차순위 승계(WinnerReassignedEvent) 구현 예정.
        // 현재는 CLOSED + winnerId 상태로 유지된다.
    }

    @KafkaHandler(isDefault = true)
    public void handleUnknown(Object event) {
        log.warn("알 수 없는 이벤트 수신(payment-events). type={}", event.getClass().getName());
    }
}
