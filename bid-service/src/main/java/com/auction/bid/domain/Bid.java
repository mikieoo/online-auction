package com.auction.bid.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "bid")
public class Bid {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "bid_id")
    private Long bidId;

    @Column(name = "auction_id", nullable = false)
    private Long auctionId;

    @Column(name = "bidder_id", nullable = false)
    private Long bidderId;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BidStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected Bid() {
    }

    public Bid(Long auctionId, Long bidderId, BigDecimal amount) {
        this.auctionId = auctionId;
        this.bidderId = bidderId;
        this.amount = amount;
        this.status = BidStatus.ACTIVE;
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 낙찰 확정. ACTIVE → WINNER 전이만 허용한다.
     */
    public void markWinner() {
        if (this.status != BidStatus.ACTIVE) {
            throw new IllegalStateException(
                    "낙찰 처리할 수 없는 입찰 상태입니다. 현재 상태: " + this.status);
        }
        this.status = BidStatus.WINNER;
    }

    /**
     * 더 높은 입찰에 밀림. ACTIVE → OUTBID 전이만 허용한다.
     */
    public void markOutbid() {
        if (this.status != BidStatus.ACTIVE) {
            throw new IllegalStateException(
                    "OUTBID 처리할 수 없는 입찰 상태입니다. 현재 상태: " + this.status);
        }
        this.status = BidStatus.OUTBID;
    }

    public Long getBidId() { return bidId; }
    public Long getAuctionId() { return auctionId; }
    public Long getBidderId() { return bidderId; }
    public BigDecimal getAmount() { return amount; }
    public BidStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
