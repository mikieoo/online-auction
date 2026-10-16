# D21: ACTIVE 유일성을 DB가 강제하게 하기 — 생성 컬럼 + UNIQUE

## 오늘 한 일

"상품당 ACTIVE 경매 1개", "경매당 ACTIVE 입찰 1개"는 지금까지 애플리케이션 코드만 지켰다. 경매 시작은 "ACTIVE가 있나?"를 조회만 하고 락 없이 통과시켰고, 입찰은 분산 락에 기댔다. MySQL에는 조건부 UNIQUE 인덱스가 없어서 DB로는 못 막는다고 봤기 때문이다. 오늘 생성 컬럼으로 조건부 UNIQUE를 흉내 내 두 불변 조건을 DB가 강제하게 했다. 같은 상품 경매 두 개를 동시에 시작하는 경합을 k6로 100번 재현했더니 **100번 모두** 두 요청이 사전 검사를 함께 통과했고, 제약이 매번 한쪽을 막았다.

---

## 1. 생성 컬럼으로 조건부 UNIQUE 흉내 내기

```sql
-- auction
active_product_id BIGINT GENERATED ALWAYS AS (IF(status = 'ACTIVE', product_id, NULL)) VIRTUAL,
UNIQUE KEY uk_auction_active_product (active_product_id)

-- bid
active_auction_id BIGINT GENERATED ALWAYS AS (IF(status = 'ACTIVE', auction_id, NULL)) VIRTUAL,
UNIQUE KEY uk_bid_active_auction (active_auction_id)
```

| status | active_product_id | UNIQUE 충돌 |
|---|---|---|
| ACTIVE | product_id 값 | 같은 상품의 다른 ACTIVE와 충돌 |
| WAITING·CLOSED·COMPLETED·FAILED | NULL | 없음 (UNIQUE는 NULL끼리 충돌하지 않는다) |

- **VIRTUAL인 이유**: 행에는 저장하지 않고 인덱스에만 값이 들어간다. InnoDB는 가상 생성 컬럼에 보조 인덱스를 걸 수 있다.
- **엔티티에 매핑하지 않는다**: 생성 컬럼은 INSERT/UPDATE 대상이 될 수 없다. 매핑하면 Hibernate가 컬럼 목록에 넣어 MySQL이 거부한다. 상태가 바뀌면 MySQL이 알아서 다시 계산한다.

## 2. 사전 검사는 왜 남겼나

DB 제약만 있어도 정합성은 지켜진다. 그래도 `ensureNoActiveAuction`과 분산 락을 남겼다.

- **사전 검사**: 대부분의 위반 요청을 DB 쓰기 전에 거절한다. 오류 메시지도 명확하다.
- **분산 락**: 같은 경매의 입찰을 줄 세워 "충돌 → 재시도" 대신 "순서대로 처리 → 정상 거절"을 만든다(D7). 제약은 정합성만 지키고 사용자 경험은 지키지 않는다.
- **DB 제약**: 위 둘이 뚫렸을 때(동시 통과, Redis 장애, 검사를 빠뜨린 새 코드 경로)의 최종 방어다.

## 3. 위반을 그 자리에서 받기

```java
// AuctionService.startAuction
auction.start();
try {
    return auctionRepository.saveAndFlush(auction);
} catch (DataIntegrityViolationException e) {
    if (violates(e, "uk_auction_active_product")) throw activeAuctionExists(productId); // 409
    throw e;                                                                              // 다른 위반은 그대로
}
```

- **`saveAndFlush`인 이유**: `save`만 하면 UPDATE가 커밋 시점으로 미뤄진다. 위반이 트랜잭션 프록시 밖에서 `DataIntegrityViolationException`으로 나가 500이 된다. 즉시 flush해서 서비스 안에서 받아 409로 바꾼다.
- **어떤 제약인지 가리기**: 원인 메시지 `Duplicate entry '5' for key 'auction.uk_auction_active_product'`의 인덱스 이름으로 판단한다. 다른 무결성 위반(PK·FK)을 "ACTIVE 중복"으로 잘못 바꾸지 않기 위해서다.
- **이벤트**: 위반 시 예외가 던져져 트랜잭션이 롤백되므로, outbox의 `AuctionStartedEvent`도 기록되지 않는다.
- **입찰 쪽**: `uk_bid_active_auction` 위반을 `BID_CONFLICT`(409, "다시 시도")로 바꾼다.
- **로그**: 두 경우 모두 "…DB 제약이 막음" WARN을 남겨, 사전 검사를 통과한 경합이 얼마나 자주 생기는지 볼 수 있게 했다.

## 4. 함정: IDENTITY + 새 ACTIVE INSERT

제약을 걸자마자 **모든 정상 입찰이 실패할 뻔했다.** 입찰 저장은 "이전 ACTIVE를 OUTBID로 → 새 ACTIVE 저장" 순서다.

- `Bid` id가 `IDENTITY`라서, 새 Bid는 `save` 순간 INSERT가 나간다(id를 받아야 하니까).
- 이전 Bid의 OUTBID 변경은 dirty checking이라 flush 때까지 미뤄진다.
- 그래서 INSERT 시점의 DB에는 이전 Bid가 아직 ACTIVE다. ACTIVE가 2개가 되어 제약에 걸린다.

한 flush에 몰아도 해결되지 않는다. Hibernate는 flush 때 INSERT를 UPDATE보다 먼저 실행하기 때문이다. 그래서 `saveAndFlush(previous)`로 OUTBID UPDATE를 먼저 내보낸 뒤 새 입찰을 저장한다. 단위 테스트(`InOrder`)로 이 순서를 고정했다.

## 5. 검증

**SQL 수준** (bid_db)

- ACTIVE 두 개 INSERT → `ERROR 1062 Duplicate entry '999001' for key 'bid.uk_bid_active_auction'`
- 같은 경매에 OUTBID·DISQUALIFIED 여러 개 → 허용
- 먼저 OUTBID로 UPDATE한 뒤 새 ACTIVE INSERT → 허용

**경합 재현** (`infra/k6/concurrent-start.js`, 같은 상품의 WAITING 경매 두 개를 `http.batch`로 동시 시작, 100회)

| 항목 | 결과 |
|---|---|
| 정확히 하나만 시작(200 + 409 `ACTIVE_AUCTION_EXISTS`) | 100 / 100 |
| 둘 다 시작 | 0 |
| DB 제약이 막은 횟수 (auction-service WARN 로그) | **100** |
| ACTIVE 경매가 2개 이상인 상품 | 0 |

제약이 막은 횟수가 100이라는 건, 두 요청이 **매번** 사전 검사를 함께 통과했다는 뜻이다. D20까지의 코드였다면 100번 모두 ACTIVE 경매가 2개 생겼다. 복습 때 "판매자가 동시에 두 번 눌러야 해서 빈도가 낮다"고 봤는데, 동시에 들어온 요청이면 사전 검사 하나로는 거의 막지 못한다. 경합 빈도는 감으로 판단하지 말고 재현해서 재야 한다.

**회귀** (D20 시나리오)

| 시나리오 | 결과 |
|---|---|
| 인기 경매 초당 150건 | 409 0건, 불변 조건 통과, p95 32~95ms (스택을 새로 띄운 직후 첫 실행만 334ms) |
| 인기 경매 초당 100건 | p95 13ms |
| 경매 200개 동시 마감 | 26.1초에 전부 정산, 최종 상태 200/200 일치 |
| 입찰 DB 제약 발동 / bid-service ERROR 로그 | 0 / 0 |

정상 흐름에서는 입찰 제약이 한 번도 발동하지 않는다. 분산 락이 제대로 동작하는 한 이 제약은 쓰이지 않는 최종 방어선이다.

## 6. 덤: 스키마 원본이 세 서비스 모두 뒤처져 있었다

규칙상 서비스의 `schema.sql`이 원본이고 `infra/mysql/init/`이 사본인데, 원본 셋이 모두 사본보다 뒤처져 있었다.

| 원본 | 빠져 있던 것 |
|---|---|
| auction | `processed_event` 테이블 (D12) |
| bid | `version` 컬럼 (D6) |
| payment | `outbox`, `shedlock` 테이블 (D11) |

서비스는 기동 시 `schema.sql`을 실행하지 않는다. MySQL은 `spring.sql.init` 기본 대상(내장 DB)이 아니어서, 원본이 어긋나도 아무 오류가 나지 않았다. 사본 기준으로 원본을 다시 맞췄다.

---

## 면접 대비 포인트

**Q: MySQL에서 "상태가 ACTIVE인 행만 유일"은 어떻게 강제하나?**
→ 조건부 유니크 인덱스가 없으니 생성 컬럼 `IF(status='ACTIVE', product_id, NULL)`에 UNIQUE를 건다. UNIQUE는 NULL끼리 충돌하지 않으므로 ACTIVE 행끼리만 충돌한다. PostgreSQL이라면 `CREATE UNIQUE INDEX ... WHERE status = 'ACTIVE'`로 바로 된다.

**Q: 분산 락이 있는데 DB 제약이 왜 필요한가?**
→ 락은 Redis가 살아 있고, 모든 코드 경로가 락을 거칠 때만 지켜진다. 제약은 둘 다 아닐 때도 지켜진다. 락은 사용자 경험(충돌 대신 순서대로 처리), 제약은 정합성이라 역할이 다르다. `@Version`은 UPDATE 경합만 막고 INSERT 경합은 못 막아서 그 빈자리를 제약이 채운다.

**Q: 제약을 걸었더니 정상 요청이 실패한다면 뭘 의심하나?**
→ 쓰기 순서다. IDENTITY id는 `save` 순간 INSERT하지만 dirty checking UPDATE는 flush까지 미뤄진다. 게다가 Hibernate는 flush 때도 INSERT를 UPDATE보다 먼저 보낸다. "ACTIVE를 내리는 UPDATE → flush → ACTIVE를 만드는 INSERT" 순서를 코드로 강제해야 한다.

**Q: 제약 위반을 어떻게 사용자 오류로 바꾸나?**
→ `saveAndFlush`로 위반을 서비스 안에서 받고, 원인 메시지의 인덱스 이름으로 어떤 제약인지 가려 409로 바꾼다. 다른 무결성 위반은 그대로 던진다. 커밋 시점까지 미루면 프록시 밖에서 500으로 나간다.

**Q: "드문 경합"이라 미뤄 둬도 되나?**
→ 이번에 재 보니 동시에 들어온 두 요청이 100번 중 100번 사전 검사를 함께 통과했다. 조회 후 검사는 동시성 앞에서 거의 무력하다. 빈도는 추측하지 말고 재현해서 잰다.
