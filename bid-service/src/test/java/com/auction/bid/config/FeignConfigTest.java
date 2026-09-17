package com.auction.bid.config;

import com.auction.bid.client.AuctionClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.CircuitBreakerNameResolver;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class FeignConfigTest {

    @Test
    @DisplayName("서킷 브레이커 이름은 메서드 시그니처가 아니라 Feign 클라이언트 이름이다 (yml instances.auction-service와 매칭)")
    void circuitBreakerNameIsFeignClientName() throws NoSuchMethodException {
        CircuitBreakerNameResolver resolver = new FeignConfig().circuitBreakerNameResolver();
        Method method = AuctionClient.class.getMethod("getAuction", Long.class);

        assertThat(resolver.resolveCircuitBreakerName("auction-service", null, method))
                .isEqualTo("auction-service");
    }
}
