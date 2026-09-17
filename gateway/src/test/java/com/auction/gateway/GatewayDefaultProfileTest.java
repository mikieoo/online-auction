package com.auction.gateway;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "gateway.jwt.secret=test-only-secret-0123456789-abcdefghij",
            "eureka.client.enabled=false"
        })
class GatewayDefaultProfileTest {

    @Autowired
    private WebTestClient client;

    @Test
    void local_프로필이_아니면_토큰_발급_경로는_404다() {
        client.post().uri("/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("userId", 7))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("NOT_FOUND")
                .jsonPath("$.timestamp").doesNotExist();
    }

    @Test
    void internal_경로는_라우트가_없어_404다() {
        client.get().uri("/internal/v1/anything")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("NOT_FOUND")
                .jsonPath("$.timestamp").doesNotExist();
    }

    @Test
    void actuator는_health와_서킷_브레이커_엔드포인트만_노출한다() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
        client.get().uri("/actuator/circuitbreakers").exchange().expectStatus().isOk();
        client.get().uri("/actuator/circuitbreakerevents").exchange().expectStatus().isOk();
        client.get().uri("/actuator/env").exchange().expectStatus().isNotFound();
    }
}
