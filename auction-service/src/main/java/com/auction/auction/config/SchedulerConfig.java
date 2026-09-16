package com.auction.auction.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;

/**
 * 스케줄링·ShedLock 활성화. Application 클래스에 두지 않는 이유는 FeignConfig와 같다(테스트가 DataSource를 요구하지 않게).
 * 락 레코드는 auction_db의 shedlock 테이블(schema.sql)에 저장되며, usingDbTime()으로 인스턴스 간 시계 차이를 배제한다.
 * defaultLockAtMostFor(PT30S)는 인스턴스가 죽어 락을 못 풀었을 때의 최대 보유 시간이다.
 * 틱당 처리 건수 × Feign 타임아웃 합이 lockAtMostFor를 넘으면 락 만료 후 중복 실행 가능 — 자세한 계산은 application.yml 주석 참고.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT30S")
public class SchedulerConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }
}
