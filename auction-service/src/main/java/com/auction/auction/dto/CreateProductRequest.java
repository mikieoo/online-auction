package com.auction.auction.dto;

import java.math.BigDecimal;

public class CreateProductRequest {

    private String name;
    private String description;
    private BigDecimal startingPrice;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getStartingPrice() { return startingPrice; }
    public void setStartingPrice(BigDecimal startingPrice) { this.startingPrice = startingPrice; }
}
