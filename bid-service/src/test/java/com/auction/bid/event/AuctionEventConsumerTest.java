package com.auction.bid.event;

import com.auction.common.event.AuctionClosedEvent;
import com.auction.common.event.AuctionStartedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * D8 컨슈머는 로그만 남기므로 예외 없이 실행되는지 확인한다.
 * 향후 캐시 적용 시 이 테스트를 확장한다.
 */
class AuctionEventConsumerTest {

    private final AuctionEventConsumer consumer = new AuctionEventConsumer();

    @Test
    @DisplayName("AuctionStartedEvent 수신 시 예외 없이 처리한다")
    void handleAuctionStarted_doesNotThrow() {
        AuctionStartedEvent event = new AuctionStartedEvent(
                "evt-1", LocalDateTime.now(), 100L, 1L, new BigDecimal("50000"), LocalDateTime.now().plusHours(1));

        assertThatCode(() -> consumer.handleAuctionStarted(event)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AuctionClosedEvent 수신 시 예외 없이 처리한다")
    void handleAuctionClosed_doesNotThrow() {
        AuctionClosedEvent event = new AuctionClosedEvent("evt-2", LocalDateTime.now(), 100L);

        assertThatCode(() -> consumer.handleAuctionClosed(event)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("알 수 없는 이벤트 수신 시 예외 없이 처리한다")
    void handleUnknown_doesNotThrow() {
        assertThatCode(() -> consumer.handleUnknown("unexpected")).doesNotThrowAnyException();
    }
}
