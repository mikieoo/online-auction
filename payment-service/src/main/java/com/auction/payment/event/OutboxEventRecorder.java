package com.auction.payment.event;

import com.auction.payment.domain.OutboxEvent;
import com.auction.payment.repository.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * {@code @Transactional} 메서드 안에서 호출하여 outbox 테이블에 이벤트를 기록한다.
 * 비즈니스 데이터와 같은 트랜잭션으로 커밋되므로 이벤트 유실이 없다.
 */
@Component
public class OutboxEventRecorder {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OutboxEventRecorder(OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    public void record(String aggregateType, Long aggregateId, Object event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            String eventType = event.getClass().getName();
            outboxRepository.save(OutboxEvent.create(aggregateType, aggregateId, eventType, payload));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Outbox 이벤트 직렬화 실패", e);
        }
    }
}
