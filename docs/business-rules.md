# 도메인 규칙

## 엔티티 관계

- Product 1 : N Auction (단, ACTIVE 상태의 Auction은 동시에 최대 1개)
- Auction 1 : N Bid (bid-service 소유, auction_id로 참조). ACTIVE 상태의 Bid는 경매당 최대 1개.
- Auction 1 : N Payment (payment-service 소유, auction_id로 참조). Payment는 idempotencyKey당 1개.

## 상태 전이

### Auction

```
WAITING ──(판매자 수동 시작)──→ ACTIVE
ACTIVE  ──(스케줄러 마감)────→ CLOSED
CLOSED  ──(입찰 0건)────────→ FAILED (유찰)
CLOSED  ──(낙찰자 결제 성공)──→ COMPLETED
CLOSED  ──(3회 결제 실패 or 입찰자 소진)──→ FAILED
```

- WAITING → ACTIVE: 판매자가 수동으로 시작 API를 호출해야만 전이. start_time 기록. **end_time이 현재 시각 이전이면 시작을 거절한다**(시작 즉시 유찰되는 경매를 막는다).
- ACTIVE → CLOSED: 스케줄러가 end_time 도달 시 마감. ShedLock으로 인스턴스 간 중복 방지. 마감은 외부 서비스 상태와 무관하게 항상 수행된다.
- 진행 중(ACTIVE) 경매는 판매자가 취소할 수 없다.
- CLOSED → FAILED: 낙찰 확정 결과가 "입찰 없음"이면 유찰로 즉시 전이. 낙찰/결제 흐름 없음.
- CLOSED → COMPLETED: 낙찰자의 결제가 성공하면 전이. 낙찰자·낙찰가는 이 시점에 함께 기록된다.
- CLOSED → FAILED: 결제 실패로 차순위 승계를 3회 시도했거나 입찰자가 소진되면 전이. (차순위 승계는 아직 구현 전 — 현재는 결제 실패 시 낙찰자만 기록하고 CLOSED에 머문다.)
- 낙찰자 지정(assignWinner)은 CLOSED 상태에서만 허용된다. 다른 상태에서의 지정은 거부된다.

불가능한 전이: ACTIVE → WAITING, COMPLETED → 모든 상태, FAILED → 모든 상태.

### 정산 진행 단계 읽는 법 (현재 구현)

별도 정산 상태 없이 `status`와 `winner_id`의 조합으로 판단한다.

| status | winner_id | 의미 |
|---|---|---|
| CLOSED | NULL | 정산 대기 — 스케줄러가 다음 주기에 낙찰 확정·결제를 (재)시도 |
| CLOSED | 있음 | 낙찰자는 확정됐으나 결제 실패 — 차순위 승계 대기 |
| COMPLETED | 있음 | 결제 성공 |
| FAILED | NULL | 유찰(입찰 0건) |

결제 완료 전에는 조회 API의 winnerId가 비어 있다. 이 표현은 이벤트 기반 정산으로 바뀔 때 재설계된다.

### Bid

```
ACTIVE ──(더 높은 입찰)──→ OUTBID
ACTIVE ──(낙찰 확정)────→ WINNER
WINNER ──(결제 실패 차순위 승계)──→ OUTBID
```

- 새 입찰이 접수되면 그 경매의 기존 ACTIVE Bid(같은 입찰자의 것이어도)는 OUTBID가 되고 새 Bid가 ACTIVE가 된다. 따라서 "현재 최고가"는 ACTIVE Bid의 금액이다.
- 낙찰 확정은 ACTIVE Bid를 WINNER로 바꾼다. 이미 WINNER가 있으면 그대로 유지(재확정은 같은 결과). ACTIVE도 WINNER도 없으면 "입찰 없음".
- 취소 상태는 없다(입찰 철회 불가).

불가능한 전이: OUTBID → ACTIVE, OUTBID → WINNER, WINNER → WINNER.

### Payment

```
REQUESTED ──(결제 성공)──→ COMPLETED
REQUESTED ──(결제 실패)──→ FAILED
```

결제는 시뮬레이션. COMPLETED/FAILED에서 다른 상태로의 전이 없음. REQUESTED로 남은 결제(처리 중 중단)는 같은 키로 다시 요청되면 그때 확정된다.

## 입찰 규칙

입찰 요청은 아래 순서로 검사하고 첫 실패에서 거절한다.

1. 금액은 필수이며 0보다 커야 한다.
2. 경매가 존재해야 한다. bid-service가 auction-service에 동기 조회한다.
3. 판매자 본인의 경매에는 입찰 불가 (sellerId == bidderId → 거부).
4. 경매가 ACTIVE 상태여야 한다. ACTIVE여도 end_time이 현재 시각 이하이면 거부한다(스케줄러가 아직 마감하지 않은 틈을 막는다).
5. 금액 규칙 — 첫 입찰(ACTIVE Bid 없음): amount >= startingPrice. 이후 입찰: amount > 현재 ACTIVE Bid 금액 + 증가 단위. 증가 단위는 시스템 고정값(기본 1,000원, 튜닝 가능). 경계값: 정확히 "최고가 + 증가 단위"인 금액은 거부, 그보다 1원이라도 크면 허용.
6. 동일 경매에 같은 사용자가 복수 입찰 가능 (금액 올리기). 이전 입찰은 OUTBID가 된다.
7. 입찰 후 철회(취소) 불가.
8. 현재 최고 입찰가는 bid-service가 소유. 입찰 검증의 주체.

## 동시 입찰 직렬화

목표: auctionId 기준 분산 락(Redisson)으로 동시 입찰을 직렬화한다. 마감 처리도 같은 락 범위에 포함되어 마감 직전 입찰과 충돌 시 먼저 락을 획득한 쪽이 실행된다.

현재 상태: 락 없음. 같은 경매에 동시 입찰이 들어오면 검사와 저장 사이의 경합으로 ACTIVE Bid가 2개 생길 수 있다. 낙관적 락(D6), 분산 락(D7)에서 해결한다.

## 마감·정산

스케줄러(auction-service)가 주기적으로(기본 5초) 두 단계를 수행한다.

1. **마감**: ACTIVE이고 end_time이 지난 경매를 각각 CLOSED로 바꾼다. 외부 호출 없음.
2. **정산**: CLOSED이고 낙찰자가 없는 경매마다 (a) bid-service에 낙찰 확정을 요청하고, (b) 입찰이 없으면 FAILED(유찰), 있으면 payment-service에 결제를 요청한다. 결제 성공 → 낙찰자 기록 + COMPLETED. 결제 실패 → 낙찰자만 기록, CLOSED 유지. 외부 호출이 실패하거나 결제가 아직 확정되지 않았으면 아무것도 바꾸지 않고 다음 주기에 다시 시도한다.

경매 1건의 처리 실패는 다른 경매의 처리를 막지 않는다. 재시도는 멱등해야 한다: 낙찰 확정은 같은 WINNER를 돌려주고, 결제는 같은 멱등성 키의 기존 결제를 돌려준다.

## 결제 시뮬레이션

결제 금액의 정수부(원)를 10진 문자열로 보았을 때 설정된 실패 접미사(기본 `9999`)로 끝나면 실패, 아니면 성공. 소수부는 무시한다. 예: 19,999원 → 실패, 20,000원 → 성공, 19,999.50원 → 실패. 실패 사유는 `SIMULATED_FAILURE`. 실패를 의도적으로 재현할 수 있어야 하므로 확률 기반 규칙은 쓰지 않는다.

## 차순위 승계

결제 실패 시 auction-service가 bid-service에 다음 최고 입찰자를 동기 조회한다. 이전 낙찰자의 Bid 상태를 OUTBID로 변경한 뒤 새 낙찰자에게 WinnerReassigned 이벤트를 발행한다. 최대 3회. 입찰자 소진 또는 3회 초과 시 경매는 FAILED. (D10에서 구현. 현재 Bid 규칙 "OUTBID → WINNER 불가"와 충돌하는 점은 미해결 문제로 기록되어 있다.)

## 멱등성 키

idempotencyKey 형태: `{auctionId}-{winnerId}-{reassignmentCount}`. auction-service가 결제를 요청할 때(현재는 동기 호출, 이후 AuctionWon/WinnerReassigned 이벤트) 생성하여 함께 보낸다. payment-service는 같은 키의 결제가 있으면 새로 만들지 않고 기존 것을 돌려준다 — 요청 본문의 금액이 달라도 기존 것이 우선한다(키가 identity).

## 사용자

별도의 user-service 없음. 각 서비스는 userId(BIGINT)만 참조. 판매자/구매자 역할 구분 없음 — 모든 사용자가 판매·입찰 가능(본인 경매 입찰만 금지). 결제 내역은 결제한 본인만 조회할 수 있다.
