package com.auction.auction.exception;

import org.springframework.http.HttpStatus;

/** 409 — 상태 충돌. 코드 예: ACTIVE_AUCTION_EXISTS, AUCTION_ALREADY_ENDED, INVALID_STATE_TRANSITION */
public class ConflictException extends AuctionServiceException {

    public ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
