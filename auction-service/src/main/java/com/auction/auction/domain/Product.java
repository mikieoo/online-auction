package com.auction.auction.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "product")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_id")
    private Long productId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "starting_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal startingPrice;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Product() {
    }

    public Product(Long sellerId, String name, String description, BigDecimal startingPrice) {
        this.sellerId = sellerId;
        this.name = name;
        this.description = description;
        this.startingPrice = startingPrice;
        this.createdAt = LocalDateTime.now();
    }

    public Long getProductId() { return productId; }
    public Long getSellerId() { return sellerId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public BigDecimal getStartingPrice() { return startingPrice; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
