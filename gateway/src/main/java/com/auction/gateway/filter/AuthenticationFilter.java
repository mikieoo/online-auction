package com.auction.gateway.filter;

import com.auction.gateway.exception.ErrorResponseWriter;
import com.auction.gateway.exception.InvalidTokenException;
import com.auction.gateway.exception.TokenExpiredException;
import com.auction.gateway.security.JwtTokenService;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 내부 서비스는 X-User-Id를 그대로 신뢰한다. 그래서 이 헤더는 Gateway만 쓸 수 있어야 한다:
 * 클라이언트가 보낸 값은 항상 지우고, 토큰 검증에 성공했을 때만 sub로 다시 채운다.
 */
@Component
public class AuthenticationFilter implements GlobalFilter, Ordered {

    public static final String USER_ID_HEADER = "X-User-Id";

    /** CircuitBreaker·LoadBalancer·NettyRouting 등 라우팅 필터보다 먼저 실행돼야 실패한 요청이 하류로 나가지 않는다. */
    private static final int ORDER = -100;
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String PAYMENTS_PATH = "/api/v1/payments";

    private final JwtTokenService jwtTokenService;
    private final ErrorResponseWriter errorResponseWriter;

    public AuthenticationFilter(JwtTokenService jwtTokenService, ErrorResponseWriter errorResponseWriter) {
        this.jwtTokenService = jwtTokenService;
        this.errorResponseWriter = errorResponseWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (hasSuspiciousPath(request)) {
            // Gateway는 경로를 정규화하지 않고 원문 그대로 하류에 넘긴다. "/api/v1/bids/../../internal/..." 같은 경로가
            // 라우트 predicate에는 매칭되므로, 차단을 하류 MVC의 동작에 맡기지 않고 여기서 끊는다.
            // 이 API의 경로는 영문·숫자·'/'·'-'만 쓰므로 인코딩(%), 세미콜론 매트릭스 변수, 역슬래시, "..", "//"는 전부 비정상이다.
            return errorResponseWriter.write(exchange, HttpStatus.NOT_FOUND, "NOT_FOUND", "요청한 경로를 찾을 수 없습니다.");
        }
        if (!requiresAuthentication(request)) {
            // 공개 조회는 토큰이 있어도 검증하지 않는다 — 만료된 토큰을 들고 있다는 이유로 공개 조회가 막히면 안 된다.
            return chain.filter(withUserId(exchange, null));
        }
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null
                || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return unauthorized(exchange, "UNAUTHORIZED", "인증 토큰이 필요합니다.");
        }
        long userId;
        try {
            userId = jwtTokenService.parseUserId(authorization.substring(BEARER_PREFIX.length()).trim());
        } catch (TokenExpiredException e) {
            return unauthorized(exchange, "TOKEN_EXPIRED", "토큰이 만료되었습니다. 다시 발급받으세요.");
        } catch (InvalidTokenException e) {
            // 실패 사유(서명/형식/sub)를 구분해 알려주면 공격자에게 힌트가 된다. 하나의 메시지로 응답한다.
            return unauthorized(exchange, "UNAUTHORIZED", "유효하지 않은 토큰입니다.");
        }
        return chain.filter(withUserId(exchange, userId));
    }

    /**
     * 인증 대상: GET이 아닌 모든 요청 + 결제 조회(GET /api/v1/payments/**).
     * 현재 라우트는 전부 /api/** 아래라 "GET이 아니면 인증"으로 닫아 둔다 — 새 라우트가 추가돼도 기본이 "인증 필요"다.
     */
    /** 원문(raw) 경로 기준으로 검사한다 — 디코딩된 값으로 보면 "%2e%2e" 같은 변형을 놓친다. */
    private boolean hasSuspiciousPath(ServerHttpRequest request) {
        String rawPath = request.getURI().getRawPath();
        if (rawPath == null) {
            return false;
        }
        return rawPath.contains("%") || rawPath.contains(";") || rawPath.contains("\\")
                || rawPath.contains("..") || rawPath.contains("//");
    }

    private boolean requiresAuthentication(ServerHttpRequest request) {
        if (!HttpMethod.GET.equals(request.getMethod())) {
            return true;
        }
        String path = request.getPath().pathWithinApplication().value();
        return path.equals(PAYMENTS_PATH) || path.startsWith(PAYMENTS_PATH + "/");
    }

    private ServerWebExchange withUserId(ServerWebExchange exchange, Long userId) {
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(USER_ID_HEADER);
                    if (userId != null) {
                        headers.set(USER_ID_HEADER, Long.toString(userId));
                    }
                })
                .build();
        return exchange.mutate().request(mutated).build();
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String code, String message) {
        return errorResponseWriter.write(exchange, HttpStatus.UNAUTHORIZED, code, message);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
