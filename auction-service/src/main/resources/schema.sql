CREATE TABLE IF NOT EXISTS product (
    product_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    seller_id BIGINT NOT NULL,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    starting_price DECIMAL(15, 2) NOT NULL,
    created_at DATETIME(6) NOT NULL
);

CREATE TABLE IF NOT EXISTS auction (
    auction_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'WAITING',
    start_time DATETIME(6),
    end_time DATETIME(6) NOT NULL,
    winner_id BIGINT,
    winning_price DECIMAL(15, 2),
    reassignment_count INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_auction_product FOREIGN KEY (product_id) REFERENCES product (product_id),
    INDEX idx_auction_status_end_time (status, end_time),
    INDEX idx_auction_product_id (product_id)
);

CREATE TABLE IF NOT EXISTS outbox (
    outbox_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    INDEX idx_outbox_published_created_at (published, created_at)
);

-- ShedLock 분산 락 테이블 (스케줄러 다중 인스턴스 중복 실행 방지)
CREATE TABLE IF NOT EXISTS shedlock (
    name VARCHAR(64) NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at TIMESTAMP(3) NOT NULL,
    locked_by VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
