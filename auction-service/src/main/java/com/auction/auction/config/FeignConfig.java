package com.auction.auction.config;

import org.springframework.cloud.openfeign.CircuitBreakerNameResolver;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 활성화를 Application 클래스가 아닌 여기에 두어 MockMvc standalone·Mockito 단위 테스트가 Feign 빈을 요구하지 않게 한다.
 * Retryer는 기본값(NEVER_RETRY)을 그대로 둔다. 재시도는 스케줄러의 다음 주기가 담당하며, 두 클라이언트 모두 멱등 호출이다.
 * ErrorDecoder를 두지 않는다. 비 2xx는 FeignException으로 올라오고 스케줄러가 "이 틱은 건너뜀"으로 처리한다.
 * 4xx가 FeignClientException 타입 그대로 올라와야 서킷 브레이커의 ignore-exceptions(application.yml)가 동작하므로,
 * ErrorDecoder를 추가해 예외 타입을 바꾸면 업무 4xx가 브레이커를 열게 된다.
 * 클라이언트별 타임아웃은 {@link BidClientConfig}, {@link PaymentClientConfig}에서 각 {@code @FeignClient(configuration=...)}로 주입된다.
 * 타임아웃 수단은 Feign connect/read 타임아웃 하나뿐이다(브레이커 TimeLimiter는 yml에서 끈다).
 */
@Configuration
@EnableFeignClients(basePackages = "com.auction.auction.client")
public class FeignConfig {

    /**
     * 브레이커 이름을 Feign 클라이언트 이름(bid-service, payment-service)으로 고정한다.
     * 기본 이름은 "BidClient#confirmWinner(Long)" 같은 메서드 시그니처라서
     * resilience4j.circuitbreaker.instances.* 설정과 매칭되지 않고(=기본 설정으로 조용히 동작) actuator에서도 읽기 어렵다.
     */
    @Bean
    public CircuitBreakerNameResolver circuitBreakerNameResolver() {
        return (feignClientName, target, method) -> feignClientName;
    }
}
