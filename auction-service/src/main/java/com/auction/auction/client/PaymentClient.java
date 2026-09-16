package com.auction.auction.client;

import com.auction.auction.config.PaymentClientConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * payment-service 결제 요청 클라이언트. auction-service는 payment_db를 직접 읽지 않고 이 HTTP 계약만 사용한다.
 * 같은 idempotencyKey로 재호출하면 기존 Payment를 그대로 돌려주므로(200) 스케줄러 재시도에 안전하다.
 */
@FeignClient(name = "payment-service", url = "${clients.payment-service.url:http://localhost:8083}",
        configuration = PaymentClientConfig.class)
public interface PaymentClient {

    @PostMapping("/internal/v1/payments")
    PaymentResponse requestPayment(@RequestBody PaymentRequest request);
}
