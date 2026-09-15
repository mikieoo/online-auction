package com.auction.bid.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "bid")
public class Bid {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long bidId;

    @Column(nullable = false)
    private Long auctionId;

    @Column(nullable = false)
    private Long bidderId;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BidStatus status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Bid() {}

    public Bid(Long auctionId, Long bidderId, BigDecimal amount) {
        this.auctionId = auctionId;
        this.bidderId = bidderId;
        this.amount = amount;
        this.status = BidStatus.ACTIVE;
        this.createdAt = LocalDateTime.now();
    }

    public void markWinning() {
        this.status = BidStatus.WINNING;
    }

    public void markOutbid() {
        this.status = BidStatus.OUTBID;
    }

    public void cancel() {
        this.status = BidStatus.CANCELLED;
    }

    public Long getBidId() { return bidId; }
    public Long getAuctionId() { return auctionId; }
    public Long getBidderId() { return bidderId; }
    public BigDecimal getAmount() { return amount; }
    public BidStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
