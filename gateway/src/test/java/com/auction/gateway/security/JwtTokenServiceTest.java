package com.auction.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.auction.gateway.config.JwtProperties;
import com.auction.gateway.exception.InvalidTokenException;
import com.auction.gateway.exception.TokenExpiredException;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class JwtTokenServiceTest {

    private static final String SECRET = "test-only-secret-0123456789-abcdefghij";
    private static final String OTHER_SECRET = "another-test-secret-0123456789-abcdefg";
    private static final Duration TTL = Duration.ofHours(1);

    private final JwtTokenService service = new JwtTokenService(new JwtProperties(SECRET, TTL));

    @Test
    void 발급한_토큰을_검증하면_userId가_돌아온다() {
        String token = service.issue(7L);

        assertThat(service.parseUserId(token)).isEqualTo(7L);
        assertThat(service.getTtlSeconds()).isEqualTo(3600L);
    }

    @Test
    void 만료된_토큰은_TokenExpiredException으로_분류된다() {
        Clock twoHoursAgo = Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC);
        String expired = new JwtTokenService(new JwtProperties(SECRET, TTL), twoHoursAgo).issue(7L);

        assertThatThrownBy(() -> service.parseUserId(expired)).isInstanceOf(TokenExpiredException.class);
    }

    @Test
    void 다른_키로_서명된_토큰은_거절된다() {
        String forged = new JwtTokenService(new JwtProperties(OTHER_SECRET, TTL)).issue(7L);

        assertThatThrownBy(() -> service.parseUserId(forged)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void 형식이_깨진_토큰은_거절된다() {
        assertThatThrownBy(() -> service.parseUserId("not-a-jwt")).isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> service.parseUserId("")).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void sub가_양의_정수가_아니면_거절된다() {
        for (String sub : new String[] {"abc", "0", "-3", "07", "7.5", "99999999999999999999"}) {
            assertThatThrownBy(() -> service.parseUserId(signedWithSubject(sub)))
                    .as("sub=%s", sub)
                    .isInstanceOf(InvalidTokenException.class);
        }
        assertThatThrownBy(() -> service.parseUserId(signedWithSubject(null)))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void HS256이_아닌_알고리즘으로_서명된_토큰은_거절된다() {
        String hs512 = Jwts.builder()
                .subject("7")
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(new SecretKeySpec((SECRET + SECRET).getBytes(StandardCharsets.UTF_8), "HmacSHA512"),
                        Jwts.SIG.HS512)
                .compact();

        assertThatThrownBy(() -> service.parseUserId(hs512)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void 키가_없거나_32바이트_미만이면_생성에_실패한다() {
        assertThatThrownBy(() -> new JwtTokenService(new JwtProperties(null, TTL)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtTokenService(new JwtProperties("", TTL)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtTokenService(new JwtProperties("a".repeat(31), TTL)))
                .isInstanceOf(IllegalStateException.class);
        new JwtTokenService(new JwtProperties("a".repeat(32), TTL));
    }

    @Test
    void 양수가_아닌_userId로는_발급하지_않는다() {
        assertThatThrownBy(() -> service.issue(0L)).isInstanceOf(IllegalArgumentException.class);
    }

    private String signedWithSubject(String subject) {
        return Jwts.builder()
                .subject(subject)
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"), Jwts.SIG.HS256)
                .compact();
    }
}
