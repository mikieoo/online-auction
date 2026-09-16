package com.auction.auction.config;

import feign.Request;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;

/**
 * BidClient 전용 Feign 설정. {@code @Configuration}을 붙이지 않는다 — 붙이면 전역 빈으로 등록되어
 * 다른 클라이언트에도 적용되므로, {@code @FeignClient(configuration = BidClientConfig.class)}로만 참조한다.
 */
public class BidClientConfig {

    @Bean
    public Request.Options bidClientRequestOptions(
            @Value("${clients.bid-service.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${clients.bid-service.read-timeout-ms:5000}") long readTimeoutMs) {
        return new Request.Options(
                connectTimeoutMs, TimeUnit.MILLISECONDS,
                readTimeoutMs, TimeUnit.MILLISECONDS,
                true);
    }
}
