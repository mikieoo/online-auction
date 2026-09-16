package com.auction.payment.controller;

import com.auction.payment.domain.Payment;
import com.auction.payment.dto.PaymentProcessResult;
import com.auction.payment.dto.PaymentRequest;
import com.auction.payment.exception.GlobalExceptionHandler;
import com.auction.payment.exception.PaymentAccessDeniedException;
import com.auction.payment.exception.PaymentNotFoundException;
import com.auction.payment.service.PaymentService;
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

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

    private static final String VALID_BODY =
            "{ \"auctionId\": 1, \"payerId\": 7, \"amount\": 15000, \"idempotencyKey\": \"1-7-0\" }";

    @Mock
    private PaymentService paymentService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new InternalPaymentController(paymentService),
                        new PaymentController(paymentService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private Payment payment(Long id) {
        Payment payment = new Payment(1L, 7L, new BigDecimal("15000"), "1-7-0");
        ReflectionTestUtils.setField(payment, "paymentId", id);
        return payment;
    }

    @Test
    @DisplayName("POST /internal/v1/payments 신규 → 201 + PaymentResponse")
    void createReturns201() throws Exception {
        Payment created = payment(10L);
        created.complete();
        when(paymentService.process(any(PaymentRequest.class)))
                .thenReturn(PaymentProcessResult.created(created));

        mockMvc.perform(post("/internal/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentId").value(10))
                .andExpect(jsonPath("$.auctionId").value(1))
                .andExpect(jsonPath("$.payerId").value(7))
                .andExpect(jsonPath("$.amount").value(15000))
                .andExpect(jsonPath("$.idempotencyKey").value("1-7-0"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.failureReason").value(nullValue()))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    @DisplayName("POST /internal/v1/payments 기존 키 → 200")
    void existingReturns200() throws Exception {
        Payment existing = payment(10L);
        existing.fail("SIMULATED_FAILURE");
        when(paymentService.process(any(PaymentRequest.class)))
                .thenReturn(PaymentProcessResult.existing(existing));

        mockMvc.perform(post("/internal/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("SIMULATED_FAILURE"));
    }

    @Test
    @DisplayName("POST 필수 필드 누락 → 400 INVALID_REQUEST")
    void missingFieldsReturns400() throws Exception {
        mockMvc.perform(post("/internal/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"auctionId\": 1 }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").exists());
        verify(paymentService, never()).process(any());
    }

    @Test
    @DisplayName("POST 금액 0 이하 → 400 INVALID_REQUEST")
    void nonPositiveAmountReturns400() throws Exception {
        mockMvc.perform(post("/internal/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"auctionId\": 1, \"payerId\": 7, \"amount\": 0, \"idempotencyKey\": \"1-7-0\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("POST 키 64자 초과 → 400 INVALID_REQUEST")
    void tooLongKeyReturns400() throws Exception {
        String longKey = "k".repeat(65);
        mockMvc.perform(post("/internal/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"auctionId\": 1, \"payerId\": 7, \"amount\": 100, \"idempotencyKey\": \""
                                + longKey + "\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("POST 본문이 JSON이 아니면 → 400 INVALID_REQUEST")
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/internal/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("GET /api/v1/payments/{id} 본인 결제 → 200")
    void getReturns200() throws Exception {
        when(paymentService.getPayment(10L, 7L)).thenReturn(payment(10L));

        mockMvc.perform(get("/api/v1/payments/10").header("X-User-Id", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(10))
                .andExpect(jsonPath("$.status").value("REQUESTED"));
    }

    @Test
    @DisplayName("GET X-User-Id 누락 → 400 INVALID_REQUEST")
    void getWithoutHeaderReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/payments/10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verify(paymentService, never()).getPayment(any(), any());
    }

    @Test
    @DisplayName("GET 없는 결제 → 404 PAYMENT_NOT_FOUND")
    void getNotFoundReturns404() throws Exception {
        when(paymentService.getPayment(eq(99L), eq(7L)))
                .thenThrow(new PaymentNotFoundException(99L));

        mockMvc.perform(get("/api/v1/payments/99").header("X-User-Id", "7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("GET payerId 불일치 → 403 FORBIDDEN")
    void getForbiddenReturns403() throws Exception {
        when(paymentService.getPayment(eq(10L), eq(8L)))
                .thenThrow(new PaymentAccessDeniedException(10L));

        mockMvc.perform(get("/api/v1/payments/10").header("X-User-Id", "8"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("IllegalStateException → 409 INVALID_STATE_TRANSITION")
    void illegalStateReturns409() throws Exception {
        when(paymentService.getPayment(10L, 7L))
                .thenThrow(new IllegalStateException("REQUESTED 상태에서만 완료 처리할 수 있습니다."));

        mockMvc.perform(get("/api/v1/payments/10").header("X-User-Id", "7"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("예상치 못한 예외 → 500 INTERNAL_ERROR")
    void unexpectedReturns500() throws Exception {
        when(paymentService.getPayment(10L, 7L)).thenThrow(new RuntimeException("boom"));

        mockMvc.perform(get("/api/v1/payments/10").header("X-User-Id", "7"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }
}
