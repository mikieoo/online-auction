package com.auction.auction.event;

import com.auction.auction.domain.OutboxEvent;
import com.auction.auction.repository.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * outbox 테이블을 폴링하여 미발행 이벤트를 Kafka로 전송한다.
 *
 * <p>발행 순서는 created_at 순(동일 aggregate 안에서 인과 순서 유지).
 * 발행 실패 시 해당 이벤트에서 멈추고 다음 폴링에서 재시도한다(at-least-once).
 * 컨슈머의 멱등성이 중복 수신을 흡수한다.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final String TOPIC = "auction-events";

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public OutboxPublisher(OutboxRepository outboxRepository,
                           KafkaTemplate<String, Object> kafkaTemplate,
                           ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval:1000}")
    @SchedulerLock(name = "outbox-publisher", lockAtLeastFor = "PT0.5S")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxRepository.findTop100ByPublishedFalseOrderByCreatedAtAsc();
        for (OutboxEvent event : events) {
            try {
                Class<?> eventClass = Class.forName(event.getEventType());
                Object eventObj = objectMapper.readValue(event.getPayload(), eventClass);
                String key = String.valueOf(event.getAggregateId());

                kafkaTemplate.send(TOPIC, key, eventObj).get(5, TimeUnit.SECONDS);
                event.markPublished();
                log.info("Outbox 이벤트 발행 완료. outboxId={}, type={}, aggregateId={}",
                        event.getOutboxId(), eventClass.getSimpleName(), event.getAggregateId());
            } catch (Exception e) {
                log.error("Outbox 이벤트 발행 실패, 다음 폴링에서 재시도. outboxId={}, eventType={}",
                        event.getOutboxId(), event.getEventType(), e);
                break;
            }
        }
    }
}
