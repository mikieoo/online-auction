# bid-service

## 범위

입찰(Bid) 접수·검증, 현재 최고 입찰가 소유, 입찰자 순위 조회.

## 범위 밖

- 경매 생명주기 관리, 낙찰 결정 → auction-service
- 결제 → payment-service

## 불변 조건

- 입찰은 ACTIVE 상태의 경매에만 가능. auction-service에 동기 조회하여 확인.
- 첫 입찰 >= startingPrice. 이후 입찰 > 현재 최고가 + 증가 단위(기본 1,000원).
- 판매자 본인 경매에는 입찰 불가.
- 입찰 후 철회 불가.
- 동시 입찰은 auctionId 기준 분산 락으로 직렬화 (D7에서 적용).

## DB

bid_db — bid 테이블. 인덱스: (auction_id, amount DESC), (auction_id, bidder_id). 스키마 원본: `src/main/resources/schema.sql`.

## 테스트 가이드

- 입찰 금액 검증 (시작가 미만, 증가 단위 미달)
- 본인 경매 입찰 거부
- ACTIVE가 아닌 경매 입찰 거부
- 동시 입찰 직렬화 (D7)
