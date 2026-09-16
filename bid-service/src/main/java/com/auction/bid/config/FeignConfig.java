package com.auction.bid.config;

import com.auction.bid.client.AuctionClientErrorDecoder;
import feign.Request;
import feign.codec.ErrorDecoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Feign 활성화를 Application 클래스가 아닌 여기에 두어 슬라이스 테스트가 Feign 빈을 요구하지 않게 한다.
 * Retryer는 기본값(NEVER_RETRY)을 그대로 둔다. 재시도는 D7 이후 회복 탄력성 작업에서 다룬다.
 */
@Configuration
@EnableFeignClients(basePackages = "com.auction.bid.client")
public class FeignConfig {

    @Bean
    public Request.Options feignRequestOptions(
            @Value("${clients.auction-service.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${clients.auction-service.read-timeout-ms:5000}") long readTimeoutMs) {
        return new Request.Options(
                connectTimeoutMs, TimeUnit.MILLISECONDS,
                readTimeoutMs, TimeUnit.MILLISECONDS,
                true);
    }

    @Bean
    public ErrorDecoder auctionClientErrorDecoder() {
        return new AuctionClientErrorDecoder();
    }
}
