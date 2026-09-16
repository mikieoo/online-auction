package com.auction.bid.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 마감 시각 비교에 쓰는 시계. 테스트에서 Clock.fixed로 대체할 수 있도록 빈으로 분리한다.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
