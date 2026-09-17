package com.auction.gateway.controller;

import com.auction.gateway.dto.TokenRequest;
import com.auction.gateway.dto.TokenResponse;
import com.auction.gateway.security.JwtTokenService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 비밀번호 확인 없이 아무 userId로나 토큰을 내주는 개발용 발급기다.
 * local 프로필에서만 빈으로 등록해, 다른 환경에서는 경로 자체가 없도록(404) 한다.
 */
@RestController
@Profile("local")
public class TokenController {

    private final JwtTokenService jwtTokenService;

    public TokenController(JwtTokenService jwtTokenService) {
        this.jwtTokenService = jwtTokenService;
    }

    @PostMapping("/auth/token")
    public Mono<TokenResponse> issue(@Valid @RequestBody TokenRequest request) {
        String token = jwtTokenService.issue(request.getUserId());
        return Mono.just(new TokenResponse(token, "Bearer", jwtTokenService.getTtlSeconds()));
    }
}
