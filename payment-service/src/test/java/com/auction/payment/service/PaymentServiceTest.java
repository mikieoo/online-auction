package com.auction.payment.service;

import com.auction.payment.domain.Payment;
import com.auction.payment.domain.PaymentStatus;
import com.auction.payment.dto.PaymentProcessResult;
import com.auction.payment.dto.PaymentRequest;
import com.auction.payment.exception.PaymentAccessDeniedException;
import com.auction.payment.exception.PaymentNotFoundException;
import com.auction.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        PaymentSimulator simulator = new PaymentSimulator("9999");
        PaymentTransactionService transactionService =
                new PaymentTransactionService(paymentRepository, simulator);
        paymentService = new PaymentService(paymentRepository, transactionService);
    }

    private PaymentRequest request(String amount, String key) {
        PaymentRequest request = new PaymentRequest();
        request.setAuctionId(1L);
        request.setPayerId(7L);
        request.setAmount(new BigDecimal(amount));
        request.setIdempotencyKey(key);
        return request;
    }

    private Payment persisted(Long id, String amount, String key) {
        Payment payment = new Payment(1L, 7L, new BigDecimal(amount), key);
        ReflectionTestUtils.setField(payment, "paymentId", id);
        return payment;
    }

    @Test
    @DisplayName("신규 키 + 접미사 미일치 금액 → 생성 후 COMPLETED, created=true")
    void newKeyCompletes() {
        when(paymentRepository.findByIdempotencyKey("1-7-0")).thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentProcessResult result = paymentService.process(request("20000", "1-7-0"));

        assertThat(result.isCreated()).isTrue();
        assertThat(result.getPayment().getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(result.getPayment().getFailureReason()).isNull();
        assertThat(result.getPayment().getIdempotencyKey()).isEqualTo("1-7-0");
    }

    @Test
    @DisplayName("신규 키 + 접미사 일치 금액(소수부 무시) → FAILED, failureReason=SIMULATED_FAILURE")
    void newKeyFails() {
        when(paymentRepository.findByIdempotencyKey("1-7-0")).thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentProcessResult result = paymentService.process(request("19999.50", "1-7-0"));

        assertThat(result.isCreated()).isTrue();
        assertThat(result.getPayment().getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(result.getPayment().getFailureReason()).isEqualTo("SIMULATED_FAILURE");
    }

    @Test
    @DisplayName("같은 키 재요청 → 기존 결제 반환, 본문 금액이 달라도 기존 것을 돌려준다")
    void existingKeyReturnsExisting() {
        Payment existing = persisted(10L, "20000", "1-7-0");
        existing.complete();
        when(paymentRepository.findByIdempotencyKey("1-7-0")).thenReturn(Optional.of(existing));

        PaymentProcessResult result = paymentService.process(request("99999", "1-7-0"));

        assertThat(result.isCreated()).isFalse();
        assertThat(result.getPayment()).isSameAs(existing);
        assertThat(result.getPayment().getAmount()).isEqualByComparingTo("20000");
        assertThat(result.getPayment().getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        verify(paymentRepository, never()).saveAndFlush(any());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("같은 키의 FAILED 결제 재요청 → 그대로 FAILED 반환")
    void existingFailedReturnsAsIs() {
        Payment existing = persisted(10L, "19999", "1-7-0");
        existing.fail("SIMULATED_FAILURE");
        when(paymentRepository.findByIdempotencyKey("1-7-0")).thenReturn(Optional.of(existing));

        PaymentProcessResult result = paymentService.process(request("20000", "1-7-0"));

        assertThat(result.isCreated()).isFalse();
        assertThat(result.getPayment().getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("REQUESTED로 잔존한 결제 재요청 → 재시뮬레이션 후 확정, created=false")
    void staleRequestedIsResettled() {
        Payment stale = persisted(10L, "19999", "1-7-0");
        when(paymentRepository.findByIdempotencyKey("1-7-0")).thenReturn(Optional.of(stale));
        when(paymentRepository.findById(10L)).thenReturn(Optional.of(stale));

        PaymentProcessResult result = paymentService.process(request("19999", "1-7-0"));

        assertThat(result.isCreated()).isFalse();
        assertThat(result.getPayment().getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(result.getPayment().getFailureReason()).isEqualTo("SIMULATED_FAILURE");
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("REQUESTED 잔존 건은 저장된 금액 기준으로 재시뮬레이션한다")
    void staleRequestedUsesStoredAmount() {
        Payment stale = persisted(10L, "20000", "1-7-0");
        when(paymentRepository.findByIdempotencyKey("1-7-0")).thenReturn(Optional.of(stale));
        when(paymentRepository.findById(10L)).thenReturn(Optional.of(stale));

        PaymentProcessResult result = paymentService.process(request("19999", "1-7-0"));

        assertThat(result.getPayment().getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    }

    @Test
    @DisplayName("동시 삽입으로 UNIQUE 위반 → 키로 재조회한 기존 결제 반환, created=false")
    void uniqueViolationFallsBackToExisting() {
        Payment winner = persisted(10L, "20000", "1-7-0");
        winner.complete();
        when(paymentRepository.findByIdempotencyKey("1-7-0"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(paymentRepository.saveAndFlush(any(Payment.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry '1-7-0'"));

        PaymentProcessResult result = paymentService.process(request("20000", "1-7-0"));

        assertThat(result.isCreated()).isFalse();
        assertThat(result.getPayment()).isSameAs(winner);
        verify(paymentRepository, times(2)).findByIdempotencyKey("1-7-0");
    }

    @Test
    @DisplayName("UNIQUE 위반 후 재조회에도 없으면 원래 예외를 전파한다")
    void uniqueViolationWithoutExistingRethrows() {
        when(paymentRepository.findByIdempotencyKey("1-7-0")).thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry"));

        assertThatThrownBy(() -> paymentService.process(request("20000", "1-7-0")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("조회: 존재하고 payerId 일치 → 반환")
    void getPaymentOk() {
        Payment payment = persisted(10L, "20000", "1-7-0");
        when(paymentRepository.findById(10L)).thenReturn(Optional.of(payment));

        Payment found = paymentService.getPayment(10L, 7L);

        assertThat(found).isSameAs(payment);
    }

    @Test
    @DisplayName("조회: 없으면 PaymentNotFoundException")
    void getPaymentNotFound() {
        when(paymentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.getPayment(99L, 7L))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    @DisplayName("조회: payerId 불일치 → PaymentAccessDeniedException")
    void getPaymentForbidden() {
        Payment payment = persisted(10L, "20000", "1-7-0");
        when(paymentRepository.findById(10L)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentService.getPayment(10L, 8L))
                .isInstanceOf(PaymentAccessDeniedException.class);
    }
}
