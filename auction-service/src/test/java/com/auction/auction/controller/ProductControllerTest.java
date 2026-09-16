package com.auction.auction.controller;

import com.auction.auction.exception.GlobalExceptionHandler;
import com.auction.auction.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ProductControllerTest {

    @Mock
    private ProductService productService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProductController(productService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /api/v1/products: name 공백이면 400 INVALID_REQUEST")
    void createProduct_blankName() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .header("X-User-Id", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \",\"startingPrice\":1000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verify(productService, never()).createProduct(anyLong(), any());
    }

    @Test
    @DisplayName("POST /api/v1/products: startingPrice가 0 이하면 400 INVALID_REQUEST")
    void createProduct_nonPositivePrice() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .header("X-User-Id", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"노트북\",\"startingPrice\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verify(productService, never()).createProduct(anyLong(), any());
    }

    @Test
    @DisplayName("POST /api/v1/products: startingPrice 누락 시 400 INVALID_REQUEST")
    void createProduct_missingPrice() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .header("X-User-Id", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"노트북\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
