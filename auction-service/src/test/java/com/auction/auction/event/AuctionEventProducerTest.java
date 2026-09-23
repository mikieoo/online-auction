package com.auction.auction.event;

import com.auction.auction.dto.AuctionResponse;
import com.auction.common.event.AuctionClosedEvent;
import com.auction.common.event.AuctionStartedEvent;
import com.auction.common.event.AuctionWonEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuctionEventProducerTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private AuctionEventProducer producer;

    @BeforeEach
    void setUp() {
        producer = new AuctionEventProducer(kafkaTemplate);
        when(kafkaTemplate.send(any(String.class), any(String.class), any()))
                .thenReturn(new CompletableFuture<>());
    }

    private AuctionResponse sampleResponse() {
        AuctionResponse response = new AuctionResponse();
        ReflectionTestUtils.setField(response, "auctionId", 100L);
        ReflectionTestUtils.setField(response, "productId", 1L);
        ReflectionTestUtils.setField(response, "startingPrice", new BigDecimal("50000.00"));
        ReflectionTestUtils.setField(response, "endTime", LocalDateTime.of(2030, 1, 1, 0, 0));
        return response;
    }

    @Test
    @DisplayName("publishStarted: auction-events 토픽에 AuctionStartedEvent를 auctionId 키로 발행한다")
    void publishStarted_sendsToCorrectTopicAndKey() {
        producer.publishStarted(sampleResponse());

        ArgumentCaptor<AuctionStartedEvent> captor = ArgumentCaptor.forClass(AuctionStartedEvent.class);
        verify(kafkaTemplate).send(eq("auction-events"), eq("100"), captor.capture());

        AuctionStartedEvent event = captor.getValue();
        assertThat(event.getAuctionId()).isEqualTo(100L);
        assertThat(event.getProductId()).isEqualTo(1L);
        assertThat(event.getStartingPrice()).isEqualByComparingTo("50000.00");
        assertThat(event.getEndTime()).isEqualTo(LocalDateTime.of(2030, 1, 1, 0, 0));
        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getOccurredAt()).isNotNull();
    }

    @Test
    @DisplayName("publishClosed: auction-events 토픽에 AuctionClosedEvent를 auctionId 키로 발행한다")
    void publishClosed_sendsToCorrectTopicAndKey() {
        producer.publishClosed(200L);

        ArgumentCaptor<AuctionClosedEvent> captor = ArgumentCaptor.forClass(AuctionClosedEvent.class);
        verify(kafkaTemplate).send(eq("auction-events"), eq("200"), captor.capture());

        AuctionClosedEvent event = captor.getValue();
        assertThat(event.getAuctionId()).isEqualTo(200L);
        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getOccurredAt()).isNotNull();
    }

    @Test
    @DisplayName("publishWon: auction-events 토픽에 AuctionWonEvent를 auctionId 키로 발행한다")
    void publishWon_sendsToCorrectTopicAndKey() {
        producer.publishWon(300L, 77L, new BigDecimal("15000.00"), "300-77-0");

        ArgumentCaptor<AuctionWonEvent> captor = ArgumentCaptor.forClass(AuctionWonEvent.class);
        verify(kafkaTemplate).send(eq("auction-events"), eq("300"), captor.capture());

        AuctionWonEvent event = captor.getValue();
        assertThat(event.getAuctionId()).isEqualTo(300L);
        assertThat(event.getWinnerId()).isEqualTo(77L);
        assertThat(event.getWinningPrice()).isEqualByComparingTo("15000.00");
        assertThat(event.getIdempotencyKey()).isEqualTo("300-77-0");
        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getOccurredAt()).isNotNull();
    }
}
