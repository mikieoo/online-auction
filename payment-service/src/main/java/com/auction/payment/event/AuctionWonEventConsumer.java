package com.auction.payment.event;

import com.auction.common.event.AuctionWonEvent;
import com.auction.payment.domain.Payment;
import com.auction.payment.domain.PaymentStatus;
import com.auction.payment.dto.PaymentProcessResult;
import com.auction.payment.dto.PaymentRequest;
import com.auction.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * auction-events 토픽에서 AuctionWonEvent를 수신해 결제를 처리하고,
 * 결과를 PaymentCompleted/FailedEvent로 발행한다.
 *
 * <p>멱등: AuctionWonEvent의 idempotencyKey가 PaymentService.process()의 멱등 키이므로
 * 동일 이벤트를 여러 번 받아도 같은 Payment를 반환하고 같은 결과 이벤트를 발행한다.
 */
@Component
@KafkaListener(topics = "auction-events", groupId = "${spring.kafka.consumer.group-id}")
public class AuctionWonEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuctionWonEventConsumer.class);

    private final PaymentService paymentService;
    private final PaymentEventProducer eventProducer;

    public AuctionWonEventConsumer(PaymentService paymentService,
                                    PaymentEventProducer eventProducer) {
        this.paymentService = paymentService;
        this.eventProducer = eventProducer;
    }

    @KafkaHandler
    public void handleAuctionWon(AuctionWonEvent event) {
        log.info("낙찰 이벤트 수신. eventId={}, auctionId={}, winnerId={}, amount={}",
                event.getEventId(), event.getAuctionId(), event.getWinnerId(), event.getWinningPrice());
        try {
            PaymentRequest request = new PaymentRequest();
            request.setAuctionId(event.getAuctionId());
            request.setPayerId(event.getWinnerId());
            request.setAmount(event.getWinningPrice());
            request.setIdempotencyKey(event.getIdempotencyKey());

            PaymentProcessResult result = paymentService.process(request);
            Payment payment = result.getPayment();

            if (payment.getStatus() == PaymentStatus.COMPLETED) {
                eventProducer.publishCompleted(payment);
                log.info("결제 완료, PaymentCompletedEvent 발행. auctionId={}, paymentId={}",
                        event.getAuctionId(), payment.getPaymentId());
            } else if (payment.getStatus() == PaymentStatus.FAILED) {
                eventProducer.publishFailed(payment);
                log.info("결제 실패, PaymentFailedEvent 발행. auctionId={}, paymentId={}, reason={}",
                        event.getAuctionId(), payment.getPaymentId(), payment.getFailureReason());
            } else {
                log.warn("결제가 REQUESTED 상태로 남아 있습니다. auctionId={}, paymentId={}",
                        event.getAuctionId(), payment.getPaymentId());
            }
        } catch (RuntimeException e) {
            log.error("결제 처리 실패. auctionId={}, winnerId={}", event.getAuctionId(), event.getWinnerId(), e);
        }
    }

    @KafkaHandler(isDefault = true)
    public void handleUnknown(Object event) {
        log.warn("알 수 없는 이벤트 수신(auction-events). type={}", event.getClass().getName());
    }
}
