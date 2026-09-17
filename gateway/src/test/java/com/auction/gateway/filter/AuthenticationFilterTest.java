package com.auction.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.auction.gateway.config.JwtProperties;
import com.auction.gateway.exception.ErrorResponseWriter;
import com.auction.gateway.security.JwtTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class AuthenticationFilterTest {

    private static final String SECRET = "test-only-secret-0123456789-abcdefghij";

    private final JwtTokenService tokenService = new JwtTokenService(new JwtProperties(SECRET, Duration.ofHours(1)));
    private final AuthenticationFilter filter =
            new AuthenticationFilter(tokenService, new ErrorResponseWriter(new ObjectMapper()));
    private final CapturingChain chain = new CapturingChain();

    @Test
    void 공개_GET은_토큰이_있어도_헤더를_붙이지_않고_통과시킨다() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/auctions/1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.issue(7L))
                .header(AuthenticationFilter.USER_ID_HEADER, "999"));

        filter.filter(exchange, chain).block();

        assertThat(chain.forwarded).isNotNull();
        assertThat(chain.forwarded.getRequest().getHeaders().containsKey(AuthenticationFilter.USER_ID_HEADER)).isFalse();
    }

    @Test
    void 공개_GET은_깨진_토큰이어도_검증하지_않는다() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/bids?auctionId=1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer garbage"));

        filter.filter(exchange, chain).block();

        assertThat(chain.forwarded).isNotNull();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void 토큰_없는_비GET_요청은_401이고_체인을_호출하지_않는다() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/bids")
                .header(AuthenticationFilter.USER_ID_HEADER, "999"));

        filter.filter(exchange, chain).block();

        assertThat(chain.forwarded).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    void 결제_조회는_GET이어도_토큰이_없으면_401이다() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/payments/1"));

        filter.filter(exchange, chain).block();

        assertThat(chain.forwarded).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    void Bearer_형식이_아니거나_서명이_틀리면_401_UNAUTHORIZED다() {
        for (String authorization : new String[] {"Basic abc", "Bearer garbage", "Bearer "}) {
            CapturingChain localChain = new CapturingChain();
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/products")
                    .header(HttpHeaders.AUTHORIZATION, authorization));

            filter.filter(exchange, localChain).block();

            assertThat(localChain.forwarded).as(authorization).isNull();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(exchange.getResponse().getBodyAsString().block()).contains("\"code\":\"UNAUTHORIZED\"");
        }
    }

    @Test
    void 만료된_토큰은_401_TOKEN_EXPIRED다() {
        JwtTokenService shortLived = new JwtTokenService(new JwtProperties(SECRET, Duration.ofMillis(1)));
        String expired = shortLived.issue(7L);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/bids")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired));

        // exp는 초 단위로 잘려 발급 시각과 같거나 이전이 된다. 그래도 경계에 걸리지 않게 조금 기다린다.
        Mono.delay(Duration.ofMillis(50)).block();
        filter.filter(exchange, chain).block();

        assertThat(chain.forwarded).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("\"code\":\"TOKEN_EXPIRED\"");
    }

    @Test
    void 유효한_토큰이면_sub를_X_User_Id로_전달한다() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/bids")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.issue(7L)));

        filter.filter(exchange, chain).block();

        assertThat(chain.forwarded).isNotNull();
        assertThat(chain.forwarded.getRequest().getHeaders().get(AuthenticationFilter.USER_ID_HEADER))
                .containsExactly("7");
    }

    @Test
    void 위조된_X_User_Id는_토큰의_sub로_교체된다() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/payments/1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.issue(7L))
                .header(AuthenticationFilter.USER_ID_HEADER, "999"));

        filter.filter(exchange, chain).block();

        assertThat(chain.forwarded).isNotNull();
        assertThat(chain.forwarded.getRequest().getHeaders().get(AuthenticationFilter.USER_ID_HEADER))
                .containsExactly("7");
    }

    @Test
    void 라우팅_필터보다_먼저_실행된다() {
        assertThat(filter.getOrder()).isLessThan(0);
    }

    private static class CapturingChain implements GatewayFilterChain {

        private ServerWebExchange forwarded;

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            this.forwarded = exchange;
            return Mono.empty();
        }
    }
}
