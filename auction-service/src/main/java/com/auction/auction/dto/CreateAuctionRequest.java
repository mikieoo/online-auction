package com.auction.auction.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public class CreateAuctionRequest {

    @NotNull(message = "상품 ID는 필수입니다.")
    private Long productId;

    @NotNull(message = "종료 시간은 필수입니다.")
    private LocalDateTime endTime;

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
}
