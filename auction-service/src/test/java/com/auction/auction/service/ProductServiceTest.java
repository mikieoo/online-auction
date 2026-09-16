package com.auction.auction.service;

import com.auction.auction.domain.Product;
import com.auction.auction.dto.CreateProductRequest;
import com.auction.auction.exception.AuctionServiceException;
import com.auction.auction.exception.InvalidRequestException;
import com.auction.auction.exception.NotFoundException;
import com.auction.auction.repository.ProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductService productService;

    @Test
    @DisplayName("createProduct: 시작가가 0 이하면 400 INVALID_REQUEST")
    void createProduct_nonPositivePrice_invalid() {
        CreateProductRequest request = new CreateProductRequest();
        request.setName("노트북");
        request.setStartingPrice(BigDecimal.ZERO);

        Throwable t = catchThrowable(() -> productService.createProduct(1L, request));

        assertThat(t).isInstanceOf(InvalidRequestException.class);
        assertThat(((AuctionServiceException) t).getCode()).isEqualTo("INVALID_REQUEST");
        verify(productRepository, never()).save(any());
    }

    @Test
    @DisplayName("createProduct: 정상 생성")
    void createProduct_success() {
        CreateProductRequest request = new CreateProductRequest();
        request.setName("노트북");
        request.setDescription("설명");
        request.setStartingPrice(new BigDecimal("10000"));
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        Product product = productService.createProduct(1L, request);

        assertThat(product.getSellerId()).isEqualTo(1L);
        assertThat(product.getName()).isEqualTo("노트북");
        assertThat(product.getStartingPrice()).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("getProduct: 없으면 404 PRODUCT_NOT_FOUND")
    void getProduct_notFound() {
        when(productRepository.findById(5L)).thenReturn(Optional.empty());

        Throwable t = catchThrowable(() -> productService.getProduct(5L));

        assertThat(t).isInstanceOf(NotFoundException.class);
        assertThat(((AuctionServiceException) t).getCode()).isEqualTo("PRODUCT_NOT_FOUND");
    }
}
