package com.auction.auction.event;

import com.auction.auction.domain.ProcessedEvent;
import com.auction.auction.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotentHandlerTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;

    private IdempotentHandler handler;

    @BeforeEach
    void setUp() {
        handler = new IdempotentHandler(processedEventRepository);
    }

    // ---------- isAlreadyProcessed ----------

    @Test
    @DisplayName("처리된 이벤트가 없으면 false")
    void isAlreadyProcessed_notExists() {
        when(processedEventRepository.existsById("evt-1")).thenReturn(false);

        assertThat(handler.isAlreadyProcessed("evt-1")).isFalse();
    }

    @Test
    @DisplayName("처리된 이벤트가 있으면 true")
    void isAlreadyProcessed_exists() {
        when(processedEventRepository.existsById("evt-1")).thenReturn(true);

        assertThat(handler.isAlreadyProcessed("evt-1")).isTrue();
    }

    // ---------- processOnce ----------

    @Test
    @DisplayName("신규 이벤트: processed_event 저장 + 비즈니스 로직 실행 + true 반환")
    void processOnce_newEvent_executesAndReturnsTrue() {
        when(processedEventRepository.existsById("evt-new")).thenReturn(false);
        when(processedEventRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        boolean[] executed = {false};

        boolean result = handler.processOnce("evt-new", "TestHandler", () -> executed[0] = true);

        assertThat(result).isTrue();
        assertThat(executed[0]).isTrue();
        ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getEventId()).isEqualTo("evt-new");
        assertThat(captor.getValue().getHandlerName()).isEqualTo("TestHandler");
    }

    @Test
    @DisplayName("이미 처리된 이벤트: 비즈니스 로직 미실행 + false 반환")
    void processOnce_alreadyProcessed_skips() {
        when(processedEventRepository.existsById("evt-dup")).thenReturn(true);
        boolean[] executed = {false};

        boolean result = handler.processOnce("evt-dup", "TestHandler", () -> executed[0] = true);

        assertThat(result).isFalse();
        assertThat(executed[0]).isFalse();
        verify(processedEventRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("동시 처리로 UNIQUE 위반 시 비즈니스 로직 미실행 + false 반환")
    void processOnce_concurrentDuplicate_skips() {
        when(processedEventRepository.existsById("evt-race")).thenReturn(false);
        when(processedEventRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry"));
        boolean[] executed = {false};

        boolean result = handler.processOnce("evt-race", "TestHandler", () -> executed[0] = true);

        assertThat(result).isFalse();
        assertThat(executed[0]).isFalse();
    }
}
