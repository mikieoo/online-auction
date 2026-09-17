package com.auction.gateway.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

class FallbackControllerTest {

    private final WebTestClient client = WebTestClient.bindToController(new FallbackController()).build();

    @Test
    void POST에도_503_본문으로_응답한다() {
        client.post().uri("/fallback/bid-service")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE")
                .jsonPath("$.message").isEqualTo("bid-service에 일시적으로 연결할 수 없습니다.");
    }

    @Test
    void GET과_PATCH에도_같은_응답을_준다() {
        client.get().uri("/fallback/auction-service").exchange().expectStatus().isEqualTo(503);
        client.patch().uri("/fallback/auction-service").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE");
    }
}
