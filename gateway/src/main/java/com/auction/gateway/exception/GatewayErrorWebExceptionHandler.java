package com.auction.gateway.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Gateway 자신이 만든 오류를 {code, message}로 통일한다.
 * Boot 기본 핸들러(@Order(-1))보다 먼저 실행돼야 WebFlux 기본 오류 본문(timestamp, path, ...)이 새지 않는다.
 * 내부 서비스가 돌려준 오류 응답은 예외가 아니라 정상 프록시 응답이라 여기로 오지 않고 그대로 통과한다.
 */
@Component
@Order(-2)
public class GatewayErrorWebExceptionHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorWebExceptionHandler.class);

    private final ErrorResponseWriter errorResponseWriter;

    public GatewayErrorWebExceptionHandler(ErrorResponseWriter errorResponseWriter) {
        this.errorResponseWriter = errorResponseWriter;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            // 이미 헤더가 나간 응답은 고칠 수 없다. 서버가 연결을 정리하도록 넘긴다.
            return Mono.error(ex);
        }
        if (ex instanceof ResponseStatusException statusException) {
            HttpStatusCode status = statusException.getStatusCode();
            if (status.value() == HttpStatus.NOT_FOUND.value()) {
                // 라우트 없음(/internal/** 포함). 경로 존재 여부 외의 정보는 주지 않는다.
                return errorResponseWriter.write(exchange, HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "요청한 경로를 찾을 수 없습니다.");
            }
            if (status.value() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
                // 안전망: 보통은 CircuitBreaker 필터 안에서 발생해 fallback으로 간다.
                return errorResponseWriter.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                        "서비스에 일시적으로 연결할 수 없습니다.");
            }
            if (status.value() == HttpStatus.UNAUTHORIZED.value()) {
                return errorResponseWriter.write(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다.");
            }
            if (status.is4xxClientError()) {
                // 본문 파싱·검증 실패(400), 405, 415 등 클라이언트 요청 문제. 상태 코드는 유지한다.
                HttpStatus resolved = HttpStatus.resolve(status.value());
                return errorResponseWriter.write(exchange, resolved != null ? resolved : HttpStatus.BAD_REQUEST,
                        "INVALID_REQUEST", "요청이 올바르지 않습니다.");
            }
        }
        log.error("Gateway 내부 오류: {} {}", exchange.getRequest().getMethod(), exchange.getRequest().getPath(), ex);
        return errorResponseWriter.write(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "서버 내부 오류가 발생했습니다.");
    }
}
