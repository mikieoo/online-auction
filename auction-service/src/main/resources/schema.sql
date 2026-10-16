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
    -- 상품당 ACTIVE 경매 1개를 DB가 강제한다. MySQL에는 조건부 UNIQUE 인덱스가 없어 생성 컬럼으로 흉내 낸다:
    -- ACTIVE일 때만 product_id, 아니면 NULL. UNIQUE 인덱스는 NULL끼리 충돌하지 않으므로 ACTIVE 행끼리만 충돌한다.
    -- 엔티티에 매핑하지 않는다(생성 컬럼에 값을 넣으면 오류).
    active_product_id BIGINT GENERATED ALWAYS AS (IF(status = 'ACTIVE', product_id, NULL)) VIRTUAL,
    CONSTRAINT fk_auction_product FOREIGN KEY (product_id) REFERENCES product (product_id),
    INDEX idx_auction_status_end_time (status, end_time),
    INDEX idx_auction_product_id (product_id),
    UNIQUE KEY uk_auction_active_product (active_product_id)
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

-- 멱등 컨슈머: 처리 완료 이벤트 기록
CREATE TABLE IF NOT EXISTS processed_event (
    event_id VARCHAR(64) PRIMARY KEY,
    handler_name VARCHAR(100) NOT NULL,
    processed_at DATETIME(6) NOT NULL
);

-- ShedLock 분산 락 테이블 (스케줄러 다중 인스턴스 중복 실행 방지)
CREATE TABLE IF NOT EXISTS shedlock (
    name VARCHAR(64) NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at TIMESTAMP(3) NOT NULL,
    locked_by VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
