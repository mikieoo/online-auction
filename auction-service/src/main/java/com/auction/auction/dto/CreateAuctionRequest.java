package com.auction.auction.dto;

import java.time.LocalDateTime;

public class CreateAuctionRequest {

    private Long productId;
    private LocalDateTime endTime;

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
}
