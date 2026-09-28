package com.auction.bid.domain;

public enum BidStatus {
    ACTIVE,
    OUTBID,
    WINNER,
    /** 결제 실패로 낙찰이 취소된 입찰. 차순위 승계 시 재선택 대상에서 제외된다. */
    DISQUALIFIED
}
