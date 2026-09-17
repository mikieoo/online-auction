package com.auction.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.auction.gateway.dto.TokenResponse;
import com.auction.gateway.security.JwtTokenService;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Eureka·Docker 없이 뜨는 컨텍스트 테스트. 서명 키는 테스트 코드 안에서만 준다. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "gateway.jwt.secret=test-only-secret-0123456789-abcdefghij",
            "eureka.client.enabled=false"
        })
@ActiveProfiles("local")
class GatewayLocalProfileTest {

    @Autowired
    private WebTestClient client;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private TimeLimiterRegistry timeLimiterRegistry;

    @Test
    void local_프로필에서는_토큰을_발급하고_그_토큰이_검증된다() {
        TokenResponse response = client.post().uri("/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("userId", 7))
                .exchange()
                .expectStatus().isOk()
                .expectBody(TokenResponseView.class)
                .returnResult().getResponseBody().toTokenResponse();

        assertThat(response.getTokenType()).isEqualTo("Bearer");
        assertThat(response.getExpiresIn()).isEqualTo(3600L);
        assertThat(jwtTokenService.parseUserId(response.getAccessToken())).isEqualTo(7L);
    }

    @Test
    void userId가_양의_정수가_아니면_400_INVALID_REQUEST다() {
        for (String body : new String[] {"{}", "{\"userId\":0}", "{\"userId\":-1}", "{\"userId\":\"abc\"}", "not-json"}) {
            client.post().uri("/auth/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .exchange()
                    .expectStatus().isBadRequest()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo("INVALID_REQUEST")
                    .jsonPath("$.timestamp").doesNotExist();
        }
    }

    @Test
    void 인스턴스가_없으면_POST_입찰도_fallback_503으로_응답한다() {
        client.post().uri("/api/v1/bids")
                .header("Authorization", "Bearer " + jwtTokenService.issue(7L))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("auctionId", 1, "amount", 15000))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE")
                .jsonPath("$.message").isEqualTo("bid-service에 일시적으로 연결할 수 없습니다.");

        // 라우트 필터가 실제로 만든 브레이커(이름 = 서비스명)가 yml 값을 물려받았는지 확인한다.
        assertThat(circuitBreakerRegistry.find("bid-service")).hasValueSatisfying(
                breaker -> assertThat(breaker.getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(10));
        assertThat(timeLimiterRegistry.find("bid-service")).hasValueSatisfying(
                limiter -> assertThat(limiter.getTimeLimiterConfig().getTimeoutDuration())
                        .isEqualTo(Duration.ofSeconds(8)));
    }

    @Test
    void 라우트된_요청은_토큰이_없으면_401이다() {
        client.post().uri("/api/v1/bids")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("auctionId", 1, "amount", 15000))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void 서킷_브레이커와_타임리미터가_yml_기본값을_쓴다() {
        // 앞선 요청 여부와 무관하게, Spring Cloud CircuitBreaker가 참조하는 레지스트리 default 설정을 직접 확인한다.
        assertThat(circuitBreakerRegistry.getDefaultConfig().getSlidingWindowSize()).isEqualTo(10);
        assertThat(circuitBreakerRegistry.getDefaultConfig().getMinimumNumberOfCalls()).isEqualTo(5);
        assertThat(circuitBreakerRegistry.getDefaultConfig().getFailureRateThreshold()).isEqualTo(50f);
        assertThat(circuitBreakerRegistry.getDefaultConfig().getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
        assertThat(timeLimiterRegistry.getDefaultConfig().getTimeoutDuration()).isEqualTo(Duration.ofSeconds(8));
    }

    /** TokenResponse는 불변(기본 생성자 없음)이라 역직렬화용 뷰를 테스트에만 둔다. */
    static class TokenResponseView {
        public String accessToken;
        public String tokenType;
        public long expiresIn;

        TokenResponse toTokenResponse() {
            return new TokenResponse(accessToken, tokenType, expiresIn);
        }
    }
}
