package com.auction.payment.controller;

import com.auction.payment.dto.PaymentProcessResult;
import com.auction.payment.dto.PaymentRequest;
import com.auction.payment.dto.PaymentResponse;
import com.auction.payment.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내부(서비스 간) API. Gateway를 통해 외부에 노출되지 않는다.
 */
@RestController
@RequestMapping("/internal/v1/payments")
public class InternalPaymentController {

    private final PaymentService paymentService;

    public InternalPaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> requestPayment(@Valid @RequestBody PaymentRequest request) {
        PaymentProcessResult result = paymentService.process(request);
        HttpStatus status = result.isCreated() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .body(PaymentResponse.from(result.getPayment()));
    }
}
