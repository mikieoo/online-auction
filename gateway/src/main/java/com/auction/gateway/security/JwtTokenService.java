package com.auction.gateway.security;

import com.auction.gateway.config.JwtProperties;
import com.auction.gateway.exception.InvalidTokenException;
import com.auction.gateway.exception.TokenExpiredException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** HS256 JWT 발급·검증. claims: sub(userId 문자열), iat, exp. */
@Component
public class JwtTokenService {

    /** HS256은 256비트 이상의 키를 요구한다(RFC 7518 3.2). 이보다 짧으면 서명이 무차별 대입에 약해진다. */
    private static final int MIN_SECRET_BYTES = 32;
    private static final String HS256 = "HS256";
    /** "07", "+7" 같은 변형을 같은 사용자로 받아들이지 않기 위해 정규형만 허용한다. */
    private static final Pattern POSITIVE_INTEGER = Pattern.compile("[1-9][0-9]*");

    private final SecretKey key;
    private final Duration ttl;
    private final Clock clock;

    @Autowired
    public JwtTokenService(JwtProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** 만료 시나리오를 sleep 없이 검증하기 위해 시계를 주입받는다. */
    JwtTokenService(JwtProperties properties, Clock clock) {
        String secret = properties.getSecret();
        // 키 없이 뜬 Gateway는 인증이 없는 것과 같다. 요청 시점이 아니라 기동 시점에 실패시켜 배포 단계에서 드러나게 한다.
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT 서명 키가 없습니다. 환경 변수 JWT_SECRET을 설정하세요.");
        }
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT 서명 키가 너무 짧습니다. 최소 " + MIN_SECRET_BYTES
                    + "바이트가 필요합니다. 현재: " + secretBytes.length + "바이트");
        }
        Duration configuredTtl = properties.getTtl();
        if (configuredTtl == null || configuredTtl.isZero() || configuredTtl.isNegative()) {
            throw new IllegalStateException("gateway.jwt.ttl은 0보다 커야 합니다. 현재: " + configuredTtl);
        }
        // Keys.hmacShaKeyFor는 키 길이에 따라 HS384/HS512용 키를 만든다. 알고리즘을 HS256으로 고정하기 위해 직접 만든다.
        this.key = new SecretKeySpec(secretBytes, "HmacSHA256");
        this.ttl = configuredTtl;
        this.clock = clock;
    }

    public String issue(long userId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId는 양의 정수여야 합니다. userId=" + userId);
        }
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(Long.toString(userId))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 서명·만료를 검증하고 sub를 userId로 돌려준다.
     *
     * @throws TokenExpiredException 서명은 맞지만 만료된 경우
     * @throws InvalidTokenException 그 외 모든 검증 실패
     */
    public long parseUserId(String token) {
        Jws<Claims> jws;
        try {
            jws = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token);
        } catch (ExpiredJwtException e) {
            throw new TokenExpiredException("토큰이 만료되었습니다.", e);
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException("토큰 검증에 실패했습니다.", e);
        }
        // 파서는 헤더의 alg를 따라가므로 HS384/HS512 서명도 같은 키로 검증해 줄 수 있다. 계약은 HS256뿐이라 나머지는 거절한다.
        if (!HS256.equals(jws.getHeader().getAlgorithm())) {
            throw new InvalidTokenException("허용하지 않는 서명 알고리즘입니다.");
        }
        String subject = jws.getPayload().getSubject();
        if (subject == null || !POSITIVE_INTEGER.matcher(subject).matches()) {
            throw new InvalidTokenException("토큰의 sub가 양의 정수가 아닙니다.");
        }
        try {
            return Long.parseLong(subject);
        } catch (NumberFormatException e) {
            throw new InvalidTokenException("토큰의 sub가 허용 범위를 벗어났습니다.", e);
        }
    }

    public long getTtlSeconds() {
        return ttl.toSeconds();
    }
}
