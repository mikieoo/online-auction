package com.auction.auction.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    @RestController
    @RequestMapping("/test")
    static class ThrowingController {

        @GetMapping("/not-found")
        void notFound() { throw new NotFoundException("AUCTION_NOT_FOUND", "경매를 찾을 수 없습니다. id=1"); }

        @GetMapping("/forbidden")
        void forbidden() { throw new ForbiddenException("본인의 경매만 시작할 수 있습니다."); }

        @GetMapping("/conflict")
        void conflict() { throw new ConflictException("AUCTION_ALREADY_ENDED", "이미 종료 시간이 지난 경매입니다."); }

        @GetMapping("/invalid")
        void invalid() { throw new InvalidRequestException("종료 시간은 현재 시간 이후여야 합니다."); }

        @GetMapping("/illegal-state")
        void illegalState() { throw new IllegalStateException("경매를 시작할 수 없습니다. 현재 상태: ACTIVE"); }

        @GetMapping("/illegal-argument")
        void illegalArgument() { throw new IllegalArgumentException("잘못된 인자입니다."); }

        @GetMapping("/boom")
        void boom() { throw new RuntimeException("예상치 못한 오류"); }
    }

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("NotFoundException → 404 + 코드/메시지 표준 바디")
    void notFound() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("AUCTION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("경매를 찾을 수 없습니다. id=1"));
    }

    @Test
    @DisplayName("ForbiddenException → 403 FORBIDDEN")
    void forbidden() throws Exception {
        mockMvc.perform(get("/test/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("본인의 경매만 시작할 수 있습니다."));
    }

    @Test
    @DisplayName("ConflictException → 409 + 지정 코드")
    void conflict() throws Exception {
        mockMvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUCTION_ALREADY_ENDED"));
    }

    @Test
    @DisplayName("InvalidRequestException → 400 INVALID_REQUEST")
    void invalidRequest() throws Exception {
        mockMvc.perform(get("/test/invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("잔여 IllegalStateException → 409 INVALID_STATE_TRANSITION")
    void illegalState() throws Exception {
        mockMvc.perform(get("/test/illegal-state"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"))
                .andExpect(jsonPath("$.message").value("경매를 시작할 수 없습니다. 현재 상태: ACTIVE"));
    }

    @Test
    @DisplayName("잔여 IllegalArgumentException → 400 INVALID_REQUEST")
    void illegalArgument() throws Exception {
        mockMvc.perform(get("/test/illegal-argument"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("그 외 예외 → 500 INTERNAL_ERROR")
    void internalError() throws Exception {
        mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").isString());
    }
}
