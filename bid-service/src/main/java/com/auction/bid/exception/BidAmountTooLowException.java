package com.auction.bid.exception;

import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

public class BidAmountTooLowException extends BidException {

    private BidAmountTooLowException(String message) {
        super(HttpStatus.BAD_REQUEST, "BID_AMOUNT_TOO_LOW", message);
    }

    public static BidAmountTooLowException belowStartingPrice(BigDecimal amount, BigDecimal startingPrice) {
        return new BidAmountTooLowException(
                "첫 입찰은 시작가 이상이어야 합니다. amount=" + amount + ", startingPrice=" + startingPrice);
    }

    public static BidAmountTooLowException belowIncrement(BigDecimal amount, BigDecimal currentAmount,
                                                          BigDecimal increment) {
        return new BidAmountTooLowException(
                "입찰 금액은 현재 최고가 + 증가 단위를 초과해야 합니다. amount=" + amount
                        + ", 현재 최고가=" + currentAmount + ", 증가 단위=" + increment);
    }
}
