package com.auction.gateway.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public class TokenRequest {

    @NotNull(message = "userId는 필수입니다.")
    @Positive(message = "userId는 양의 정수여야 합니다.")
    private final Long userId;

    @JsonCreator
    public TokenRequest(@JsonProperty("userId") Long userId) {
        this.userId = userId;
    }

    public Long getUserId() {
        return userId;
    }
}
