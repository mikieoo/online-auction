package com.auction.auction.event;

import com.auction.auction.dto.AuctionResponse;
import com.auction.common.event.AuctionClosedEvent;
import com.auction.common.event.AuctionStartedEvent;
import com.auction.common.event.AuctionWonEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * auction-events 토픽에 경매 이벤트를 발행한다.
 *
 * <p>트랜잭션 커밋 후 호출자(컨트롤러/스케줄러)에서 호출해야 phantom event를 피한다.
 * 발행 실패는 로그만 남기고 삼킨다 — D11 Outbox 패턴 전까지 허용되는 한계.
 */
@Component
public class AuctionEventProducer {

    private static final Logger log = LoggerFactory.getLogger(AuctionEventProducer.class);
    private static final String TOPIC = "auction-events";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public AuctionEventProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishStarted(AuctionResponse response) {
        AuctionStartedEvent event = new AuctionStartedEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                response.getAuctionId(),
                response.getProductId(),
                response.getStartingPrice(),
                response.getEndTime()
        );
        send(event.getAuctionId(), event, "AuctionStartedEvent");
    }

    public void publishWon(Long auctionId, Long winnerId, java.math.BigDecimal winningPrice,
                           String idempotencyKey) {
        AuctionWonEvent event = new AuctionWonEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                auctionId,
                winnerId,
                winningPrice,
                idempotencyKey
        );
        send(auctionId, event, "AuctionWonEvent");
    }

    public void publishClosed(Long auctionId) {
        AuctionClosedEvent event = new AuctionClosedEvent(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                auctionId
        );
        send(auctionId, event, "AuctionClosedEvent");
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
