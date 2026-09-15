package com.auction.auction.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "auction")
public class Auction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "auction_id")
    private Long auctionId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AuctionStatus status;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    @Column(name = "winner_id")
    private Long winnerId;

    @Column(name = "winning_price", precision = 15, scale = 2)
    private BigDecimal winningPrice;

    @Column(name = "reassignment_count", nullable = false)
    private int reassignmentCount;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Auction() {
    }

    public Auction(Long productId, LocalDateTime endTime) {
        this.productId = productId;
        this.status = AuctionStatus.WAITING;
        this.endTime = endTime;
        this.reassignmentCount = 0;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public void start() {
        if (this.status != AuctionStatus.WAITING) {
            throw new IllegalStateException(
                    "경매를 시작할 수 없습니다. 현재 상태: " + this.status);
        }
        this.status = AuctionStatus.ACTIVE;
        this.startTime = LocalDateTime.now();
        this.updatedAt = this.startTime;
    }

    public void close() {
        if (this.status != AuctionStatus.ACTIVE) {
            throw new IllegalStateException(
                    "경매를 마감할 수 없습니다. 현재 상태: " + this.status);
        }
        this.status = AuctionStatus.CLOSED;
        this.updatedAt = LocalDateTime.now();
    }

    public void assignWinner(Long winnerId, BigDecimal winningPrice) {
        this.winnerId = winnerId;
        this.winningPrice = winningPrice;
        this.updatedAt = LocalDateTime.now();
    }

    public void complete() {
        if (this.status != AuctionStatus.CLOSED) {
            throw new IllegalStateException(
                    "경매를 완료할 수 없습니다. 현재 상태: " + this.status);
        }
        this.status = AuctionStatus.COMPLETED;
        this.updatedAt = LocalDateTime.now();
    }

    public void fail() {
        if (this.status != AuctionStatus.CLOSED) {
            throw new IllegalStateException(
                    "경매를 실패 처리할 수 없습니다. 현재 상태: " + this.status);
        }
        this.status = AuctionStatus.FAILED;
        this.updatedAt = LocalDateTime.now();
    }

    public void incrementReassignmentCount() {
        this.reassignmentCount++;
        this.updatedAt = LocalDateTime.now();
    }

    public Long getAuctionId() { return auctionId; }
    public Long getProductId() { return productId; }
    public AuctionStatus getStatus() { return status; }
    public LocalDateTime getStartTime() { return startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public Long getWinnerId() { return winnerId; }
    public BigDecimal getWinningPrice() { return winningPrice; }
    public int getReassignmentCount() { return reassignmentCount; }
    public Long getVersion() { return version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
