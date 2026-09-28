USE payment_db;

CREATE TABLE IF NOT EXISTS payment (
    payment_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    auction_id BIGINT NOT NULL,
    payer_id BIGINT NOT NULL,
    amount DECIMAL(15, 2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
    failure_reason VARCHAR(255) NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_payment_idempotency_key (idempotency_key),
    INDEX idx_payment_auction_id (auction_id)
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

-- ShedLock 분산 락 테이블 (Outbox 폴러 다중 인스턴스 중복 실행 방지)
CREATE TABLE IF NOT EXISTS shedlock (
    name VARCHAR(64) NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at TIMESTAMP(3) NOT NULL,
    locked_by VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
