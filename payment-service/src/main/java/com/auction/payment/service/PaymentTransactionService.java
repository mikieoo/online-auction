package com.auction.payment.service;

import com.auction.payment.domain.Payment;
import com.auction.payment.exception.PaymentNotFoundException;
import com.auction.payment.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 결제 생성·확정의 트랜잭션 경계.
 *
 * PaymentService(비트랜잭션 파사드)와 분리한 이유: INSERT에서 UNIQUE 위반이 나면
 * 현재 트랜잭션이 rollback-only로 표시되므로, 같은 트랜잭션 안에서 재조회하면
 * 커밋 시 UnexpectedRollbackException이 난다. 재조회는 이 빈의 트랜잭션이 끝난 뒤
 * 파사드에서 수행해야 한다. 별도 빈으로 두어 @Transactional 프록시가 실제로 적용되게 한다.
 */
@Service
public class PaymentTransactionService {

    private final PaymentRepository paymentRepository;
    private final PaymentSimulator paymentSimulator;

    public PaymentTransactionService(PaymentRepository paymentRepository,
                                     PaymentSimulator paymentSimulator) {
        this.paymentRepository = paymentRepository;
        this.paymentSimulator = paymentSimulator;
    }

    /**
     * REQUESTED로 생성 → 시뮬레이션 → COMPLETED/FAILED 확정을 한 트랜잭션에서 수행한다.
     * saveAndFlush로 INSERT를 즉시 실행해 UNIQUE 위반이 이 메서드 호출 시점에
     * DataIntegrityViolationException으로 드러나게 한다.
     */
    @Transactional
    public Payment createAndSettle(Long auctionId, Long payerId, BigDecimal amount, String idempotencyKey) {
        Payment payment = paymentRepository.saveAndFlush(
                new Payment(auctionId, payerId, amount, idempotencyKey));
        settle(payment);
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
}
