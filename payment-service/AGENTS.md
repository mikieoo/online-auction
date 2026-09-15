# payment-service

## 범위

결제(Payment) 처리(시뮬레이션), 결제 결과 이벤트 발행.

## 범위 밖

- 경매·낙찰 관리 → auction-service
- 입찰 → bid-service

## 불변 조건

- idempotencyKey로 중복 결제 방지. 같은 키의 Payment가 이미 존재하면 새로 생성하지 않는다.
- Payment 상태는 REQUESTED → COMPLETED 또는 FAILED. 역전이 없음.

## DB

payment_db — payment 테이블. idempotency_key UNIQUE 제약. 스키마 원본: `src/main/resources/schema.sql`.

## 테스트 가이드

- 멱등성 키 중복 시 처리 (기존 Payment 반환 또는 무시)
- 결제 시뮬레이션 결과에 따른 이벤트 발행
