package com.auction.auction.controller;

import com.auction.auction.dto.CreateProductRequest;
import com.auction.auction.dto.ProductResponse;
import com.auction.auction.service.ProductService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    public ResponseEntity<ProductResponse> createProduct(
            @RequestHeader("X-User-Id") Long userId,
            @RequestBody CreateProductRequest request) {
        var product = productService.createProduct(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ProductResponse.from(product));
    }

    @GetMapping("/{productId}")
    public ResponseEntity<ProductResponse> getProduct(@PathVariable Long productId) {
        var product = productService.getProduct(productId);
        return ResponseEntity.ok(ProductResponse.from(product));
    }

    @GetMapping
    public ResponseEntity<List<ProductResponse>> getAllProducts() {
        var products = productService.getAllProducts().stream()
                .map(ProductResponse::from)
                .toList();
        return ResponseEntity.ok(products);
    }
}
