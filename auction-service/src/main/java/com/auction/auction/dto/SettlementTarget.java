package com.auction.auction.dto;

/**
 * B단계 정산 대상 한 건. 스케줄러가 트랜잭션 밖에서 Feign 호출에 필요한 최소 정보만 들고 다닌다.
 * reassignmentCount는 결제 idempotencyKey 생성에 쓰인다.
 */
public class SettlementTarget {

    private final Long auctionId;
    private final int reassignmentCount;

    public SettlementTarget(Long auctionId, int reassignmentCount) {
        this.auctionId = auctionId;
        this.reassignmentCount = reassignmentCount;
    }

    public Long getAuctionId() { return auctionId; }
    public int getReassignmentCount() { return reassignmentCount; }
}
