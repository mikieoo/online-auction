# bid-service

## 범위

입찰(Bid) 접수·검증, 현재 최고 입찰가 소유, 입찰 이력 조회, 낙찰 확정(ACTIVE → WINNER) 내부 API.

## 범위 밖

- 경매 생명주기 관리, 마감, 낙찰 결정의 시점 → auction-service (이 서비스는 요청받았을 때 WINNER로 바꾸기만 한다)
- 결제 → payment-service

## 불변 조건

- 한 경매에 ACTIVE Bid는 최대 1개. 새 입찰을 저장하기 전에 기존 ACTIVE(같은 입찰자의 것이어도)를 OUTBID로 내린다. 현재 최고가 = ACTIVE Bid의 금액.
- 입찰 검사 순서 고정: 금액 형식 → 경매 조회 → 판매자 본인(403) → ACTIVE 상태(409) → end_time 미경과(409) → 금액 규칙(400). 첫 실패에서 거절.
- 첫 입찰 >= startingPrice. 이후 입찰 > 현재 최고가 + 증가 단위(`bid.increment`, 기본 1,000원). 경계값(정확히 최고가+단위)은 거절.
- 입찰 후 철회 불가. 취소 상태 없음(`BidStatus = ACTIVE, OUTBID, WINNER`).
- 낙찰 확정은 멱등: WINNER가 있으면 그것을, 없으면 ACTIVE를 WINNER로, 둘 다 없으면 `hasBids=false`(HTTP 200). 입찰 없음을 404로 바꾸면 auction-service가 재시도 대상으로 오해한다.
- auction-service 응답에 sellerId·startingPrice가 없으면 검증을 건너뛰지 않고 502로 거절한다.
- 경매 조회에는 Circuit Breaker가 걸려 있다. fallback은 **빠른 거절**뿐이다 — 경매 상태 없이 입찰을 받는 degraded 동작을 만들지 않는다. 브레이커가 OPEN이면 즉시 503 `UPSTREAM_UNAVAILABLE`.
- 업무 예외(`AuctionNotFoundException` 404, `UpstreamErrorException` 502)는 브레이커 실패로 세지 않고(yml `ignore-exceptions`), FallbackFactory에서 **그대로 다시 던진다**. 두 목록은 항상 같이 고친다 — Resilience4j는 ignore한 예외에도 fallback을 호출하므로 rethrow가 빠지면 404가 503이 된다.
- 동시 입찰은 auctionId 기준 분산 락으로 직렬화 (D7에서 적용). 현재는 락 없음.

## 구현 패턴

- 패키지: `controller`(BidController 공개, InternalBidController 내부), `service`(BidService), `client`(AuctionClient, AuctionSummaryResponse, AuctionClientErrorDecoder), `config`(FeignConfig — `@EnableFeignClients`, 타임아웃, ErrorDecoder 빈; ClockConfig), `exception`(BidException 계층 + GlobalExceptionHandler), `dto`, `domain`, `repository`.
- Feign 오류 매핑 두 겹: HTTP 응답이 있는 오류는 `AuctionClientErrorDecoder`(404→404, 5xx→503, 그 외 4xx→502), 연결 실패·타임아웃·브레이커 OPEN은 `AuctionClientFallbackFactory`(→503). 예외 매핑은 이 두 곳에만 둔다(`BidService`에는 응답 null·필수 필드 누락 검사만 남아 있다).
- 브레이커 이름은 Feign 클라이언트 이름(`auction-service`) — `FeignConfig`의 `CircuitBreakerNameResolver`. TimeLimiter는 꺼져 있고 타임아웃은 Feign connect/read만 쓴다.
- 호출 대상은 Eureka 서비스 이름으로 찾는다. `clients.auction-service.url`을 주면 고정 주소를 쓴다(discovery 없이 단독 실행).
- 시각 비교는 주입된 `Clock`으로 한다(테스트에서 고정).

## DB

bid_db — bid 테이블(`created_at` 컬럼). 인덱스: (auction_id, amount DESC), (auction_id, bidder_id). 스키마 원본: `src/main/resources/schema.sql` (사본: `infra/mysql/init/03-bid-schema.sql`, 함께 수정).

## 테스트 가이드

- 입찰 금액 검증 (시작가 미만, 증가 단위 미달·경계값)
- 본인 경매 입찰 거부, 판매자 검사가 상태 검사보다 먼저
- ACTIVE가 아닌 경매·endTime 경과 경매 입찰 거부
- 이전 ACTIVE → OUTBID (같은 입찰자 포함)
- 상류 404/연결 실패/5xx/필드 누락 → 404/503/503/502 (BidServiceTest는 클라이언트가 `UpstreamUnavailableException`을 던지는 것으로 503을 검증한다)
- FallbackFactory 분류: 404·502 그대로, 연결 실패·5xx·`CallNotPermittedException`·래핑된 원인 → 503
- 낙찰 확정 멱등성, 입찰 없음 응답
- 동시 입찰 직렬화 (D7)
- 테스트는 Mockito(AuctionClient·BidRepository mock) + MockMvc standalone. DataSource를 띄우지 않는다.
