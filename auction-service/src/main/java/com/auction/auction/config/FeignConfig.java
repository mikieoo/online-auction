package com.auction.auction.config;

import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 활성화를 Application 클래스가 아닌 여기에 두어 MockMvc standalone·Mockito 단위 테스트가 Feign 빈을 요구하지 않게 한다.
 * Retryer는 기본값(NEVER_RETRY)을 그대로 둔다. 재시도는 스케줄러의 다음 주기가 담당하며, 두 클라이언트 모두 멱등 호출이다.
 * ErrorDecoder를 두지 않는다. 비 2xx는 FeignException으로 올라오고 스케줄러가 "이 틱은 건너뜀"으로 처리한다.
 * 클라이언트별 타임아웃은 {@link BidClientConfig}, {@link PaymentClientConfig}에서 각 {@code @FeignClient(configuration=...)}로 주입된다.
 */
@Configuration
@EnableFeignClients(basePackages = "com.auction.auction.client")
public class FeignConfig {
}
