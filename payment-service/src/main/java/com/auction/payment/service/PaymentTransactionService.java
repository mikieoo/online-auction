package com.auction.payment.service;

import com.auction.common.event.PaymentCompletedEvent;
import com.auction.common.event.PaymentFailedEvent;
import com.auction.payment.domain.Payment;
import com.auction.payment.event.OutboxEventRecorder;
import com.auction.payment.exception.PaymentNotFoundException;
import com.auction.payment.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 결제 생성·확정의 트랜잭션 경계.
 *
 * <p>PaymentService(비트랜잭션 파사드)와 분리한 이유: INSERT에서 UNIQUE 위반이 나면
 * 현재 트랜잭션이 rollback-only로 표시되므로, 같은 트랜잭션 안에서 재조회하면
 * 커밋 시 UnexpectedRollbackException이 난다. 재조회는 이 빈의 트랜잭션이 끝난 뒤
 * 파사드에서 수행해야 한다.
 *
 * <p><b>D11 Outbox:</b> 결제 확정 후 결과 이벤트(PaymentCompleted/FailedEvent)를
 * 같은 트랜잭션에서 outbox 테이블에 기록한다. Kafka 직접 발행은 하지 않는다.
 */
@Service
public class PaymentTransactionService {

    private final PaymentRepository paymentRepository;
    private final PaymentSimulator paymentSimulator;
    private final OutboxEventRecorder outboxRecorder;

    public PaymentTransactionService(PaymentRepository paymentRepository,
                                     PaymentSimulator paymentSimulator,
                                     OutboxEventRecorder outboxRecorder) {
        this.paymentRepository = paymentRepository;
        this.paymentSimulator = paymentSimulator;
        this.outboxRecorder = outboxRecorder;
    }

    /**
     * REQUESTED로 생성 → 시뮬레이션 → COMPLETED/FAILED 확정 → outbox 기록을 한 트랜잭션에서 수행한다.
     */
    @Transactional
    public Payment createAndSettle(Long auctionId, Long payerId, BigDecimal amount, String idempotencyKey) {
        Payment payment = paymentRepository.saveAndFlush(
                new Payment(auctionId, payerId, amount, idempotencyKey));
        settle(payment);
        recordOutboxEvent(payment);
        return payment;
    }

    /**
     * 이전 처리가 시뮬레이션 전에 중단되어 REQUESTED로 남은 결제를 다시 시뮬레이션해 확정한다.
     * 저장된 금액을 기준으로 판정하며, 이미 확정된 건은 그대로 반환한다.
     */
    @Transactional
    public Payment resettle(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (payment.isRequested()) {
            settle(payment);
            recordOutboxEvent(payment);
        }
        return payment;
    }

    private void settle(Payment payment) {
        if (paymentSimulator.isFailure(payment.getAmount())) {
            payment.fail(PaymentSimulator.FAILURE_REASON);
        } else {
            payment.complete();
        }
    }

    private void recordOutboxEvent(Payment payment) {
        if (payment.getStatus() == com.auction.payment.domain.PaymentStatus.COMPLETED) {
            PaymentCompletedEvent event = new PaymentCompletedEvent(
                    UUID.randomUUID().toString(), LocalDateTime.now(),
                    payment.getAuctionId(), payment.getPaymentId());
            outboxRecorder.record("Payment", payment.getAuctionId(), event);
        } else if (payment.getStatus() == com.auction.payment.domain.PaymentStatus.FAILED) {
            PaymentFailedEvent event = new PaymentFailedEvent(
                    UUID.randomUUID().toString(), LocalDateTime.now(),
                    payment.getAuctionId(), payment.getPaymentId(),
                    payment.getPayerId(), payment.getFailureReason());
            outboxRecorder.record("Payment", payment.getAuctionId(), event);
        }
    }
}
