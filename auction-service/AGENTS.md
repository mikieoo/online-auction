# auction-service

## 범위

상품(Product) 등록, 경매(Auction) 생명주기 관리, 낙찰 결정, 차순위 승계 조율. Outbox 테이블 소유(D11부터 사용).

## 범위 밖

- 입찰 접수·검증, 현재 최고가 관리 → bid-service
- 결제 처리 → payment-service
- 라우팅, JWT 검증 → gateway

## 불변 조건

- 하나의 Product에 ACTIVE 상태의 Auction은 동시에 최대 1개. 경매 생성/시작 시 애플리케이션 코드에서 검증 필수.
- Auction 상태 전이는 정해진 경로만 가능: WAITING→ACTIVE→CLOSED→COMPLETED/FAILED.
- version 필드로 낙관적 락 지원 (D6에서 적용).

## DB

auction_db — product, auction, outbox 테이블. 스키마 원본: `src/main/resources/schema.sql`.

## 테스트 가이드

- ACTIVE Auction 유일성 검증 로직
- 상태 전이 규칙 (불가능한 전이 시도 시 거부)
- 차순위 승계 흐름 (최대 3회, 입찰자 소진 시 FAILED)
