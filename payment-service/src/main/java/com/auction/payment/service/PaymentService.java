package com.auction.payment.service;

import com.auction.payment.domain.Payment;
import com.auction.payment.dto.PaymentProcessResult;
import com.auction.payment.dto.PaymentRequest;
import com.auction.payment.exception.PaymentAccessDeniedException;
import com.auction.payment.exception.PaymentNotFoundException;
import com.auction.payment.repository.PaymentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 결제 파사드. 의도적으로 @Transactional을 두지 않는다.
 * 트랜잭션 경계는 PaymentTransactionService가 갖고, 이 클래스는 멱등성 키 기준 분기와
 * UNIQUE 위반 시 재조회(반드시 실패한 트랜잭션 바깥에서)를 담당한다.
 */
@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentTransactionService transactionService;

    public PaymentService(PaymentRepository paymentRepository,
                          PaymentTransactionService transactionService) {
        this.paymentRepository = paymentRepository;
        this.transactionService = transactionService;
    }

    /**
     * idempotencyKey가 identity다. 같은 키의 결제가 있으면 요청 본문(금액 등)이 달라도 기존 것을 돌려준다.
     */
    public PaymentProcessResult process(PaymentRequest request) {
        String key = request.getIdempotencyKey();

        Optional<Payment> existing = paymentRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return PaymentProcessResult.existing(ensureSettled(existing.get()));
        }

        try {
            Payment created = transactionService.createAndSettle(
                    request.getAuctionId(),
                    request.getPayerId(),
                    request.getAmount(),
                    key);
            return PaymentProcessResult.created(created);
        } catch (DataIntegrityViolationException e) {
            // 동시 요청이 먼저 같은 키로 INSERT한 경우. 실패한 트랜잭션은 이미 종료됐으므로 여기서 재조회한다.
            Payment winner = paymentRepository.findByIdempotencyKey(key)
                    .orElseThrow(() -> e);
            return PaymentProcessResult.existing(ensureSettled(winner));
        }
    }

    public Payment getPayment(Long paymentId, Long userId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (!payment.getPayerId().equals(userId)) {
            throw new PaymentAccessDeniedException(paymentId);
        }
        return payment;
    }

    /** REQUESTED로 잔존한 건은 재시뮬레이션해 확정한 뒤 돌려준다. 이미 확정된 건은 그대로. */
    private Payment ensureSettled(Payment payment) {
        if (payment.isRequested()) {
            return transactionService.resettle(payment.getPaymentId());
        }
        return payment;
    }
}
