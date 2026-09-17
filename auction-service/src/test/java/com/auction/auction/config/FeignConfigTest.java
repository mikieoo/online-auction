package com.auction.auction.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.CircuitBreakerNameResolver;

import static org.assertj.core.api.Assertions.assertThat;

class FeignConfigTest {

    @Test
    @DisplayName("브레이커 이름은 메서드 시그니처가 아니라 Feign 클라이언트 이름이다(yml instances 키와 일치해야 설정이 적용된다)")
    void circuitBreakerNameIsFeignClientName() {
        CircuitBreakerNameResolver resolver = new FeignConfig().circuitBreakerNameResolver();

        assertThat(resolver.resolveCircuitBreakerName("bid-service", null, null)).isEqualTo("bid-service");
        assertThat(resolver.resolveCircuitBreakerName("payment-service", null, null)).isEqualTo("payment-service");
    }
}
