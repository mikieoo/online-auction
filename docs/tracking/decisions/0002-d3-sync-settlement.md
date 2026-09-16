# 0002: D3 정산 흐름 — 동기 Feign 호출과 status+winner_id 임시 인코딩

## 상황

D3 시점에는 Kafka가 없다(D8 도입). 그런데 "입찰 → 마감 → 낙찰 → 결제 → COMPLETED"가 끝까지 동작하는 시스템이 필요했고, 정산은 외부 서비스(bid, payment) 호출이 실패해도 다음 주기에 안전하게 재시도되어야 했다. 재시도 대상을 식별할 정산 상태 컬럼은 auction 테이블에 없다.

## 결정

1. 낙찰 확정과 결제 요청을 auction-service 스케줄러가 bid-service·payment-service를 **OpenFeign으로 동기 호출**해 처리한다.
2. 정산 진행 단계는 새 컬럼 없이 **`status + winner_id` 조합**으로 표현한다: `CLOSED + NULL` = 정산 대기(재시도 대상), `CLOSED + 있음` = 결제 실패(D10 대기), `COMPLETED` = 결제 성공, `FAILED + NULL` = 유찰. 낙찰자는 결제 결과를 받은 뒤에만 저장한다.
3. 결제 실패 시 경매는 CLOSED로 남긴다. 차순위 승계와 FAILED 전이는 만들지 않는다.
4. 입찰 없음은 HTTP 200 + `hasBids=false`로 표현하고, 비 2xx는 전부 재시도로 다룬다.

## 대안

- **낙찰자가 결제 API를 직접 호출하는 방식**: D9의 자동 흐름(AuctionWon 이벤트 → 결제)과 구조가 달라져 D9에서 전부 다시 짜야 한다. 채택 안 함.
- **정산 상태 컬럼 추가**(예: `settlement_status`): 스키마가 깔끔하지만 D9에서 이벤트 기반 상태 전이로 바뀌면 컬럼 의미가 또 바뀐다. 두 번 바꾸는 대신 임시 인코딩을 택했다.
- **차순위 승계를 D3에서 동기 루프로 구현**: D10 보상 트랜잭션의 학습 주제를 미리 소진하고, 이벤트 기반으로 다시 작성하게 된다. 채택 안 함.
- **입찰 없음을 404로**: 인프라 오류(경로 불일치, 라우팅)와 구분이 안 되어 경매가 잘못 FAILED(불가역)될 수 있다. 채택 안 함.

## 결과

- 세 서비스만으로 end-to-end가 동작한다. 재시도 경로는 전부 멱등(낙찰 확정은 같은 WINNER, 결제는 같은 idempotencyKey의 기존 건 반환).
- 결제 완료 전까지 조회 API에서 `winnerId`가 null이다. architecture.md의 목표 흐름("낙찰자 설정 → 결제")과 순서가 다르다.
- 결제 실패 경매는 D10까지 CLOSED에 머문다.
- **D9에서 되돌릴 것**: AuctionWon 발행 전에 낙찰자를 확정해야 하므로 winner_id 지연 저장을 걷어내고, 동기 Feign 호출을 이벤트로 대체한다. 그 전까지 이 인코딩을 전제로 하는 코드는 `AuctionSettlementService`와 `AuctionRepository.findByStatusAndWinnerIdIsNull`이다.
