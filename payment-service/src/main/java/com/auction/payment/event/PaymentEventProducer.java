package com.auction.payment.event;

import com.auction.common.event.PaymentCompletedEvent;
import com.auction.common.event.PaymentFailedEvent;
import com.auction.payment.domain.Payment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * payment-events 토픽에 결제 결과 이벤트를 발행한다.
 */
@Component
public class PaymentEventProducer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventProducer.class);
    private static final String TOPIC = "payment-events";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public PaymentEventProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishCompleted(Payment payment) {
        PaymentCompletedEvent event = new PaymentCompletedEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                payment.getAuctionId(),
                payment.getPaymentId()
        );
        send(payment.getAuctionId(), event, "PaymentCompletedEvent");
    }

    public void publishFailed(Payment payment) {
        PaymentFailedEvent event = new PaymentFailedEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                payment.getAuctionId(),
                payment.getPaymentId(),
                payment.getPayerId(),
                payment.getFailureReason()
        );
        send(payment.getAuctionId(), event, "PaymentFailedEvent");
    }

    private void send(Long auctionId, Object event, String eventType) {
        kafkaTemplate.send(TOPIC, String.valueOf(auctionId), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("이벤트 발행 실패. type={}, auctionId={}", eventType, auctionId, ex);
                    } else {
                        log.info("이벤트 발행 완료. type={}, auctionId={}, offset={}",
                                eventType, auctionId, result.getRecordMetadata().offset());
                    }
                });
    }
}
