# auction-service

## 범위

상품(Product) 등록, 경매(Auction) 생명주기 관리, 마감·정산 스케줄러(ShedLock), 낙찰 결정 조율(bid-service 호출), 결제 요청 조율(payment-service 호출), 차순위 승계 조율(D10). Outbox 테이블 소유(D11부터 사용).

## 범위 밖

- 입찰 접수·검증, 현재 최고가 관리, Bid 상태 변경 → bid-service (이 서비스는 낙찰 확정 API를 호출만 한다)
- 결제 처리·시뮬레이션 → payment-service
- 라우팅, JWT 검증 → gateway

## 불변 조건

- 하나의 Product에 ACTIVE 상태의 Auction은 동시에 최대 1개. 경매 생성/시작 시 애플리케이션 코드에서 검증 필수.
- Auction 상태 전이는 정해진 경로만 가능: WAITING→ACTIVE→CLOSED→COMPLETED/FAILED. 전이 메서드는 엔티티 안에 있고 잘못된 상태에서 호출하면 IllegalStateException(→ 409).
- `assignWinner`는 CLOSED에서만. 낙찰자는 결제 결과와 함께 저장한다(COMPLETED 또는 결제 실패 기록). `CLOSED + winner_id NULL`이 정산 재시도 대상이므로, 결제 결과를 받기 전에 winner_id를 채우는 코드는 정산을 멈추게 한다.
- 시작 시 end_time이 현재 이전이면 거절(409 AUCTION_ALREADY_ENDED).
- 스케줄러 빈에는 `@Transactional`을 두지 않는다. DB 작업은 `AuctionSettlementService`(경매 1건 = 트랜잭션 1개), Feign 호출은 그 사이에서. 한 경매의 예외는 로그 후 다음 경매로.
- 낙찰 확정 응답의 "입찰 없음"은 `hasBids=false`로만 판정한다. Feign 예외(404 포함)는 전부 "저장 없이 다음 주기 재시도".
- version 필드로 낙관적 락 지원 (D6에서 적용).

## 구현 패턴

- 패키지: `controller`(공개 API), `service`(AuctionService — API용, AuctionSettlementService — 스케줄러용 트랜잭션 단위), `scheduler`, `client`(BidClient, PaymentClient + 자체 DTO), `config`(FeignConfig, SchedulerConfig, 클라이언트별 타임아웃), `exception`(도메인 예외 + GlobalExceptionHandler), `dto`, `domain`, `repository`.
- `AuctionService`는 `AuctionResponse`(Product의 sellerId·startingPrice 포함)를 반환한다. 스케줄러는 이 서비스를 쓰지 않고 리포지토리·`AuctionSettlementService`로 엔티티를 다룬다.
- 결제 멱등키는 `PaymentRequest.idempotencyKeyOf(auctionId, winnerId, reassignmentCount)` 한 곳에서만 만든다.
- 활성화 애너테이션(`@EnableFeignClients`, `@EnableScheduling`, `@EnableSchedulerLock`)은 `config`에. Application 클래스에 옮기면 단위 테스트가 DataSource·Feign 빈을 요구하게 된다.

## DB

auction_db — product, auction, outbox, shedlock 테이블. 스키마 원본: `src/main/resources/schema.sql` (사본: `infra/mysql/init/02-auction-schema.sql`, 함께 수정).

## 테스트 가이드

- ACTIVE Auction 유일성 검증 로직
- 상태 전이 규칙 (불가능한 전이 시도 시 거부), assignWinner의 CLOSED 가드
- 시작 시 endTime 경과 거절
- 정산 분기: 입찰 없음 → FAILED / COMPLETED → winner+COMPLETED / FAILED → winner+CLOSED 유지 / Feign 실패·REQUESTED → 변경 없음 / 한 경매 예외가 다음 경매를 막지 않음 / 멱등키 형식
- 예외 처리기의 상태·code 매핑
- 차순위 승계 흐름 (최대 3회, 입찰자 소진 시 FAILED) — D10
- 테스트는 Mockito + MockMvc standalone. DataSource를 띄우지 않는다.
