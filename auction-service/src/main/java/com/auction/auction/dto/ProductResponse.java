package com.auction.auction.dto;

import com.auction.auction.domain.Product;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class ProductResponse {

    private Long productId;
    private Long sellerId;
    private String name;
    private String description;
    private BigDecimal startingPrice;
    private LocalDateTime createdAt;

    public static ProductResponse from(Product product) {
        ProductResponse response = new ProductResponse();
        response.productId = product.getProductId();
        response.sellerId = product.getSellerId();
        response.name = product.getName();
        response.description = product.getDescription();
        response.startingPrice = product.getStartingPrice();
        response.createdAt = product.getCreatedAt();
        return response;
    }

    public Long getProductId() { return productId; }
    public Long getSellerId() { return sellerId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public BigDecimal getStartingPrice() { return startingPrice; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
