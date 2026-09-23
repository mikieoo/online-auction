package com.auction.bid.event;

import com.auction.common.event.AuctionClosedEvent;
import com.auction.common.event.AuctionStartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * auction-events 토픽을 구독해 경매 시작·마감 이벤트를 수신한다.
 *
 * <p>D8에서는 수신 로그만 남긴다. 향후 활용:
 * <ul>
 *   <li>경매 상태 로컬 캐시 → 입찰 시 Feign 조회 제거</li>
 *   <li>마감 이벤트로 해당 경매 입찰 즉시 거절</li>
 * </ul>
 */
@Component
@KafkaListener(topics = "auction-events", groupId = "${spring.kafka.consumer.group-id}")
public class AuctionEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuctionEventConsumer.class);

    @KafkaHandler
    public void handleAuctionStarted(AuctionStartedEvent event) {
        log.info("경매 시작 이벤트 수신. eventId={}, auctionId={}, startingPrice={}, endTime={}",
                event.getEventId(), event.getAuctionId(), event.getStartingPrice(), event.getEndTime());
    }

    @KafkaHandler
    public void handleAuctionClosed(AuctionClosedEvent event) {
        log.info("경매 마감 이벤트 수신. eventId={}, auctionId={}",
                event.getEventId(), event.getAuctionId());
    }

    @KafkaHandler(isDefault = true)
    public void handleUnknown(Object event) {
        log.warn("알 수 없는 이벤트 수신. type={}", event.getClass().getName());
    }
}
