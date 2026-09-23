package com.auction.payment.event;

import com.auction.common.event.PaymentCompletedEvent;
import com.auction.common.event.PaymentFailedEvent;
import com.auction.payment.domain.Payment;
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
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentEventProducerTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private PaymentEventProducer producer;

    @BeforeEach
    void setUp() {
        producer = new PaymentEventProducer(kafkaTemplate);
        when(kafkaTemplate.send(any(String.class), any(String.class), any()))
                .thenReturn(new CompletableFuture<>());
    }

    private Payment completedPayment() {
        Payment payment = new Payment(100L, 77L, new BigDecimal("15000.00"), "100-77-0");
        ReflectionTestUtils.setField(payment, "paymentId", 900L);
        payment.complete();
        return payment;
    }

    private Payment failedPayment() {
        Payment payment = new Payment(200L, 88L, new BigDecimal("30000.00"), "200-88-0");
        ReflectionTestUtils.setField(payment, "paymentId", 901L);
        payment.fail("SIMULATED_FAILURE");
        return payment;
    }

    @Test
    @DisplayName("publishCompleted: payment-events 토픽에 PaymentCompletedEvent를 auctionId 키로 발행한다")
    void publishCompleted_sendsToCorrectTopicAndKey() {
        Payment payment = completedPayment();

        producer.publishCompleted(payment);

        ArgumentCaptor<PaymentCompletedEvent> captor = ArgumentCaptor.forClass(PaymentCompletedEvent.class);
        verify(kafkaTemplate).send(eq("payment-events"), eq("100"), captor.capture());

        PaymentCompletedEvent event = captor.getValue();
        assertThat(event.getAuctionId()).isEqualTo(100L);
        assertThat(event.getPaymentId()).isEqualTo(900L);
        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getOccurredAt()).isNotNull();
    }

    @Test
    @DisplayName("publishFailed: payment-events 토픽에 PaymentFailedEvent를 auctionId 키로 발행한다")
    void publishFailed_sendsToCorrectTopicAndKey() {
        Payment payment = failedPayment();

        producer.publishFailed(payment);

        ArgumentCaptor<PaymentFailedEvent> captor = ArgumentCaptor.forClass(PaymentFailedEvent.class);
        verify(kafkaTemplate).send(eq("payment-events"), eq("200"), captor.capture());

        PaymentFailedEvent event = captor.getValue();
        assertThat(event.getAuctionId()).isEqualTo(200L);
        assertThat(event.getPaymentId()).isEqualTo(901L);
        assertThat(event.getFailedPayerId()).isEqualTo(88L);
        assertThat(event.getReason()).isEqualTo("SIMULATED_FAILURE");
        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getOccurredAt()).isNotNull();
    }
}
