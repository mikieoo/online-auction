package com.auction.auction.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka 컨슈머 에러 처리 설정.
 *
 * <p><b>재시도:</b> 일시적 실패(DB 커넥션, gRPC 타임아웃 등)를 1초 간격, 최대 2회 재시도한다.
 * <b>DLQ:</b> 재시도 소진 후 {@code <원본토픽>.DLT} 토픽으로 원본 메시지를 발행한다.
 * 역직렬화 오류는 재시도 없이 즉시 DLQ로 보낸다.
 */
@Configuration
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            KafkaTemplate<String, Object> kafkaTemplate) {

        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);

        // 1초 간격, 최대 2회 재시도 (총 3회 시도)
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2));

        // 역직렬화 오류는 재시도해도 무의미 → 즉시 DLQ
        errorHandler.addNotRetryableExceptions(DeserializationException.class);

        errorHandler.setRetryListeners((record, ex, deliveryAttempt) ->
                log.warn("재시도 {}/3. topic={}, offset={}, exception={}",
                        deliveryAttempt, record.topic(), record.offset(),
                        ex.getClass().getSimpleName()));

        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
