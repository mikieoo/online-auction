package com.auction.gateway.controller;

import com.auction.gateway.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 서킷 브레이커 fallback(forward:/fallback/{서비스명})의 도착점. */
@RestController
public class FallbackController {

    /**
     * forward는 원래 요청의 HTTP 메서드를 유지한다. GET 전용 매핑이면 POST 입찰의 fallback이 405가 되므로
     * 메서드를 제한하지 않는다.
     */
    @RequestMapping("/fallback/{serviceName}")
    public Mono<ResponseEntity<ErrorResponse>> fallback(@PathVariable("serviceName") String serviceName) {
        ErrorResponse body = new ErrorResponse("SERVICE_UNAVAILABLE", serviceName + "에 일시적으로 연결할 수 없습니다.");
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body));
    }
}
