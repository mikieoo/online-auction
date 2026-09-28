package com.auction.payment.event;

import com.auction.common.event.AuctionWonEvent;
import com.auction.common.event.WinnerReassignedEvent;
import com.auction.payment.dto.PaymentProcessResult;
import com.auction.payment.dto.PaymentRequest;
import com.auction.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * auction-events 토픽에서 AuctionWonEvent/WinnerReassignedEvent를 수신해 결제를 처리한다.
 *
 * <p>멱등: idempotencyKey가 PaymentService.process()의 멱등 키이므로
 * 동일 이벤트를 여러 번 받아도 같은 Payment를 반환한다.
 *
 * <p><b>D13 Retry/DLQ:</b> 예외를 삼키지 않고 전파하여
 * {@link org.springframework.kafka.listener.DefaultErrorHandler}가 재시도/DLQ 처리한다.
 */
@Component
@KafkaListener(topics = "auction-events", groupId = "${spring.kafka.consumer.group-id}")
public class AuctionWonEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuctionWonEventConsumer.class);

    private final PaymentService paymentService;

    public AuctionWonEventConsumer(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @KafkaHandler
    public void handleAuctionWon(AuctionWonEvent event) {
        log.info("낙찰 이벤트 수신. eventId={}, auctionId={}, winnerId={}, amount={}",
                event.getEventId(), event.getAuctionId(), event.getWinnerId(), event.getWinningPrice());

        PaymentRequest request = new PaymentRequest();
        request.setAuctionId(event.getAuctionId());
        request.setPayerId(event.getWinnerId());
        request.setAmount(event.getWinningPrice());
        request.setIdempotencyKey(event.getIdempotencyKey());

        PaymentProcessResult result = paymentService.process(request);
        log.info("결제 처리 완료. auctionId={}, paymentId={}, status={}",
                event.getAuctionId(), result.getPayment().getPaymentId(),
                result.getPayment().getStatus());
    }

    @KafkaHandler
    public void handleWinnerReassigned(WinnerReassignedEvent event) {
        log.info("차순위 승계 이벤트 수신. eventId={}, auctionId={}, newWinnerId={}, amount={}",
                event.getEventId(), event.getAuctionId(), event.getNewWinnerId(), event.getNewWinningPrice());

        PaymentRequest request = new PaymentRequest();
        request.setAuctionId(event.getAuctionId());
        request.setPayerId(event.getNewWinnerId());
        request.setAmount(event.getNewWinningPrice());
        request.setIdempotencyKey(event.getIdempotencyKey());

        PaymentProcessResult result = paymentService.process(request);
        log.info("차순위 결제 처리 완료. auctionId={}, paymentId={}, status={}",
                event.getAuctionId(), result.getPayment().getPaymentId(),
                result.getPayment().getStatus());
    }

    @KafkaHandler(isDefault = true)
    public void handleUnknown(Object event) {
        log.warn("알 수 없는 이벤트 수신(auction-events). type={}", event.getClass().getName());
    }
}
