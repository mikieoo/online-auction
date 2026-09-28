package com.auction.auction.event;

import com.auction.auction.domain.ProcessedEvent;
import com.auction.auction.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 멱등 컨슈머 핵심 컴포넌트.
 *
 * <p>processed_event 테이블에 eventId를 기록하여 동일 이벤트의 중복 처리를 차단한다.
 * INSERT와 비즈니스 로직이 한 트랜잭션에서 실행되므로:
 * <ul>
 *   <li>비즈니스 로직 실패 → INSERT도 롤백 → 다음 재시도에서 다시 처리 가능</li>
 *   <li>정상 커밋 → INSERT + 비즈니스 변경이 함께 반영 → 중복 수신 시 건너뜀</li>
 * </ul>
 */
@Component
public class IdempotentHandler {

    private static final Logger log = LoggerFactory.getLogger(IdempotentHandler.class);

    private final ProcessedEventRepository processedEventRepository;

    public IdempotentHandler(ProcessedEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }

    /**
     * 읽기 전용 사전 체크. gRPC 등 외부 호출 전에 빠르게 걸러낸다.
     */
    @Transactional(readOnly = true)
    public boolean isAlreadyProcessed(String eventId) {
        return processedEventRepository.existsById(eventId);
    }

    /**
     * processed_event INSERT + 비즈니스 로직을 한 트랜잭션에서 실행한다.
     *
     * @return true=정상 처리, false=이미 처리됨(skip)
     */
    @Transactional
    public boolean processOnce(String eventId, String handlerName, Runnable action) {
        if (processedEventRepository.existsById(eventId)) {
            return false;
        }
        try {
            processedEventRepository.saveAndFlush(
                    new ProcessedEvent(eventId, handlerName, LocalDateTime.now()));
        } catch (DataIntegrityViolationException e) {
            // 동시 처리: 다른 인스턴스가 먼저 INSERT 성공
            log.info("동시 처리로 이미 기록된 이벤트. eventId={}", eventId);
            return false;
        }
        action.run();
        return true;
    }
}
