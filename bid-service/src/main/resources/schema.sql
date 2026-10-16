CREATE TABLE IF NOT EXISTS bid (
    bid_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    auction_id BIGINT NOT NULL,
    bidder_id BIGINT NOT NULL,
    amount DECIMAL(15, 2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    -- 경매당 ACTIVE 입찰 1개를 DB가 강제한다(auction.active_product_id와 같은 방식).
    -- 분산 락이 1차 방어, 이 제약이 최종 방어다: @Version은 INSERT 경합(첫 입찰 동시 저장)을 막지 못한다.
    active_auction_id BIGINT GENERATED ALWAYS AS (IF(status = 'ACTIVE', auction_id, NULL)) VIRTUAL,
    INDEX idx_bid_auction_amount (auction_id, amount DESC),
    INDEX idx_bid_auction_bidder (auction_id, bidder_id),
    UNIQUE KEY uk_bid_active_auction (active_auction_id)
);
