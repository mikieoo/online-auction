package com.auction.auction.controller;

import com.auction.auction.domain.AuctionStatus;
import com.auction.auction.dto.AuctionResponse;
import com.auction.auction.exception.GlobalExceptionHandler;
import com.auction.auction.service.AuctionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuctionControllerTest {

    @Mock
    private AuctionService auctionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AuctionController(auctionService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private AuctionResponse sampleResponse() {
        AuctionResponse response = new AuctionResponse();
        ReflectionTestUtils.setField(response, "auctionId", 100L);
        ReflectionTestUtils.setField(response, "productId", 1L);
        ReflectionTestUtils.setField(response, "sellerId", 10L);
        ReflectionTestUtils.setField(response, "startingPrice", new BigDecimal("50000.00"));
        ReflectionTestUtils.setField(response, "status", AuctionStatus.WAITING);
        ReflectionTestUtils.setField(response, "endTime", LocalDateTime.of(2030, 1, 1, 0, 0));
        ReflectionTestUtils.setField(response, "createdAt", LocalDateTime.of(2026, 1, 1, 0, 0));
        ReflectionTestUtils.setField(response, "updatedAt", LocalDateTime.of(2026, 1, 1, 0, 0));
        return response;
    }

    @Test
    @DisplayName("GET /api/v1/auctions/{id}: 응답 JSON에 sellerId, startingPrice 포함")
    void getAuction_containsSellerAndStartingPrice() throws Exception {
        when(auctionService.getAuction(100L)).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/auctions/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auctionId").value(100))
                .andExpect(jsonPath("$.productId").value(1))
                .andExpect(jsonPath("$.sellerId").value(10))
                .andExpect(jsonPath("$.startingPrice").value(50000.00))
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.reassignmentCount").value(0));
    }

    @Test
    @DisplayName("POST /api/v1/auctions: X-User-Id 헤더 없으면 400 INVALID_REQUEST")
    void createAuction_missingHeader() throws Exception {
        mockMvc.perform(post("/api/v1/auctions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1,\"endTime\":\"2030-01-01T00:00:00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verify(auctionService, never()).createAuction(anyLong(), any());
    }

    @Test
    @DisplayName("POST /api/v1/auctions: productId 누락 시 400 INVALID_REQUEST (Bean Validation)")
    void createAuction_validationFails() throws Exception {
        mockMvc.perform(post("/api/v1/auctions")
                        .header("X-User-Id", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endTime\":\"2030-01-01T00:00:00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").isString());

        verify(auctionService, never()).createAuction(anyLong(), any());
    }

    @Test
    @DisplayName("POST /api/v1/auctions: 본문이 JSON이 아니면 400 INVALID_REQUEST")
    void createAuction_unreadableBody() throws Exception {
        mockMvc.perform(post("/api/v1/auctions")
                        .header("X-User-Id", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("POST /api/v1/auctions: 정상 요청 시 201")
    void createAuction_success() throws Exception {
        when(auctionService.createAuction(eq(10L), any())).thenReturn(sampleResponse());

        mockMvc.perform(post("/api/v1/auctions")
                        .header("X-User-Id", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1,\"endTime\":\"2030-01-01T00:00:00\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.auctionId").value(100))
                .andExpect(jsonPath("$.sellerId").value(10));
    }
}
