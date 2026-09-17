package com.auction.gateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * JWT 서명 키와 토큰 수명.
 * secret에는 기본값을 두지 않는다 — 기본 키가 있으면 환경 변수를 빠뜨린 배포가 "모두가 아는 키"로 조용히 뜬다.
 * 값 검증(누락·32바이트 미만 → 기동 실패)은 키를 실제로 쓰는 JwtTokenService 생성자에서 한다.
 */
@ConfigurationProperties(prefix = "gateway.jwt")
public class JwtProperties {

    private final String secret;
    private final Duration ttl;

    public JwtProperties(String secret, @DefaultValue("PT1H") Duration ttl) {
        this.secret = secret;
        this.ttl = ttl;
    }

    public String getSecret() {
        return secret;
    }

    public Duration getTtl() {
        return ttl;
    }
}
