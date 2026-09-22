USE bid_db;

CREATE TABLE IF NOT EXISTS bid (
    bid_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    auction_id BIGINT NOT NULL,
    bidder_id BIGINT NOT NULL,
    amount DECIMAL(15, 2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    INDEX idx_bid_auction_amount (auction_id, amount DESC),
    INDEX idx_bid_auction_bidder (auction_id, bidder_id)
);
