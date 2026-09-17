package com.auction.bid.config;

import com.auction.bid.client.AuctionClientErrorDecoder;
import feign.Request;
import feign.codec.ErrorDecoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.openfeign.CircuitBreakerNameResolver;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Feign 활성화를 Application 클래스가 아닌 여기에 두어 슬라이스 테스트가 Feign 빈을 요구하지 않게 한다.
 * Retryer는 기본값(NEVER_RETRY)을 그대로 둔다. 재시도는 D7 이후 회복 탄력성 작업에서 다룬다.
 * 타임아웃은 여기의 Feign connect/read 타임아웃 하나만 쓴다. 서킷 브레이커의 TimeLimiter는 yml에서 꺼 두었다
 * (둘 다 켜면 기본 1초 TimeLimiter가 read-timeout 5초보다 먼저 끊어 설정값이 의미를 잃는다).
 * TimeLimiter가 꺼져 있으므로 Feign 호출은 호출자 스레드에서 실행된다.
 * 주의: 여기의 ErrorDecoder·Request.Options 빈은 이 서비스의 모든 Feign 클라이언트에 적용되는 전역 빈이다.
 * 클라이언트가 둘 이상 되면 auction-service처럼 클라이언트별 설정 클래스(@FeignClient(configuration=...))로 나눈다.
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

    /**
     * 서킷 브레이커 이름을 Feign 클라이언트 이름("auction-service")으로 고정한다.
     * 기본 리졸버는 "AuctionClient#getAuction(Long)" 같은 메서드 시그니처를 이름으로 쓰기 때문에
     * yml의 resilience4j.circuitbreaker.instances.auction-service(ignore-exceptions 포함)와 매칭되지 않고
     * actuator에 보이는 이름도 읽기 어렵다.
     */
    @Bean
    public CircuitBreakerNameResolver circuitBreakerNameResolver() {
        return (feignClientName, target, method) -> feignClientName;
    }
}
