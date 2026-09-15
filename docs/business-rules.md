# 도메인 규칙

## 엔티티 관계

- Product 1 : N Auction (단, ACTIVE 상태의 Auction은 동시에 최대 1개)
- Auction 1 : N Bid (bid-service 소유, auction_id로 참조)
- Auction 1 : N Payment (payment-service 소유, auction_id로 참조)

## 상태 전이

### Auction

```
WAITING ──(판매자 수동 시작)──→ ACTIVE
ACTIVE  ──(스케줄러 마감)────→ CLOSED
CLOSED  ──(입찰 0건)────────→ FAILED (유찰)
CLOSED  ──(낙찰자 결제 성공)──→ COMPLETED
CLOSED  ──(3회 결제 실패 or 입찰자 소진)──→ FAILED
```

- WAITING → ACTIVE: 판매자가 수동으로 시작 API를 호출해야만 전이. start_time 기록.
- ACTIVE → CLOSED: 스케줄러가 end_time 도달 시 마감. ShedLock으로 인스턴스 간 중복 방지.
- 진행 중(ACTIVE) 경매는 판매자가 취소할 수 없다.
- CLOSED → FAILED: 입찰이 0건이면 유찰로 즉시 전이. 낙찰/결제 흐름 없음.
- CLOSED → COMPLETED: 낙찰자의 결제가 성공하면 전이.
- CLOSED → FAILED: 결제 실패로 차순위 승계를 3회 시도했거나 입찰자가 소진되면 전이.

불가능한 전이: ACTIVE → WAITING, COMPLETED → 모든 상태, FAILED → 모든 상태.

### Bid

```
ACTIVE ──(더 높은 입찰)──→ OUTBID
ACTIVE ──(낙찰 확정)────→ WINNER
WINNER ──(결제 실패 차순위 승계)──→ OUTBID
```

불가능한 전이: OUTBID → ACTIVE, OUTBID → WINNER.

### Payment

```
REQUESTED ──(결제 성공)──→ COMPLETED
REQUESTED ──(결제 실패)──→ FAILED
```

결제는 시뮬레이션. COMPLETED/FAILED에서 다른 상태로의 전이 없음.

## 입찰 규칙

1. 경매가 ACTIVE 상태일 때만 입찰 가능. bid-service가 auction-service에 경매 상태를 동기 조회하여 확인.
2. 첫 입찰: amount >= startingPrice.
3. 이후 입찰: amount > 현재 최고가 + 시스템 고정 증가 단위(기본 1,000원, 튜닝 가능).
4. 동일 경매에 같은 사용자가 복수 입찰 가능 (금액 올리기).
5. 판매자 본인의 경매에는 입찰 불가 (sellerId == bidderId → 거부).
6. 입찰 후 철회(취소) 불가.
7. 현재 최고 입찰가는 bid-service가 소유. 입찰 검증의 주체.

## 동시 입찰 직렬화

auctionId 기준 분산 락(Redisson)으로 동시 입찰을 직렬화한다. 마감 처리도 같은 락 범위에 포함되어 마감 직전 입찰과 충돌 시 먼저 락을 획득한 쪽이 실행된다.

## 차순위 승계

결제 실패 시 auction-service가 bid-service에 다음 최고 입찰자를 동기 조회한다. 이전 낙찰자의 Bid 상태를 OUTBID로 변경한 뒤 새 낙찰자에게 WinnerReassigned 이벤트를 발행한다. 최대 3회. 입찰자 소진 또는 3회 초과 시 경매는 FAILED.

## 멱등성 키

idempotencyKey 형태: `{auctionId}-{winnerId}-{reassignmentCount}`. auction-service가 AuctionWon/WinnerReassigned 발행 시 생성하여 페이로드에 포함. payment-service가 중복 결제를 방지하는 데 사용.

## 사용자

별도의 user-service 없음. 각 서비스는 userId(BIGINT)만 참조. 판매자/구매자 역할 구분 없음 — 모든 사용자가 판매·입찰 가능(본인 경매 입찰만 금지).
