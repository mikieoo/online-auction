# 0003: Circuit Breaker 배치와 fallback 의미 — 양쪽에 걸고, fallback은 빠른 거절

## 상황

D4에서 Gateway와 Eureka를 도입하며 장애 전파를 막을 장치가 필요했다. 호출 경로는 두 층이다: 클라이언트 → Gateway → 서비스, 그리고 서비스 → 서비스(입찰 시 경매 조회, 정산 시 낙찰 확정·결제 요청). 브레이커를 어디에 둘지, 열렸을 때 무엇을 돌려줄지, 무엇을 실패로 셀지를 정해야 했다.

## 결정

1. 브레이커를 **Gateway 라우트와 서비스 간 Feign 호출 양쪽**에 둔다. 이름은 대상 서비스 이름으로 통일한다.
2. **fallback은 값을 지어내지 않는다.** 입찰의 경매 조회 fallback은 503 `UPSTREAM_UNAVAILABLE`로 즉시 거절한다. 정산 호출에는 fallback 값이 없고, 실패는 "저장 없이 다음 주기 재시도"다. Gateway fallback은 503 `SERVICE_UNAVAILABLE` 안내 응답이다.
3. **업무상 4xx는 실패로 세지 않는다**(bid: `AuctionNotFoundException`·`UpstreamErrorException` ignore, auction: `FeignClientException` ignore). Gateway는 하류가 돌려준 HTTP 응답 전체(4xx·5xx)를 실패로 세지 않는다.
4. 서비스 쪽 브레이커의 TimeLimiter는 끄고 Feign connect/read 타임아웃 하나에만 맡긴다. Gateway는 TimeLimiter를 8초로 유지한다.
5. 정산 스케줄러는 브레이커가 열려 있으면 그 주기의 남은 정산을 중단한다.

## 대안

- **Gateway에만**: 단순하지만 서비스 간 호출은 계속 타임아웃(5초)까지 기다린다. 정산 스케줄러가 죽은 서비스를 틱마다 50번 두드리고, 한 틱이 ShedLock 락 시간을 넘길 위험이 그대로 남는다.
- **Feign에만**: 내부 자원은 보호되지만 클라이언트는 죽은 서비스에 대해 매번 연결 실패 시간만큼 기다린다.
- **입찰 fallback에서 캐시된 경매 정보로 수락**: 경매 상태(ACTIVE 여부, 마감시간)가 입찰 유효성의 핵심이라 오래된 값으로 받으면 마감된 경매에 입찰이 들어간다. 채택 안 함.
- **4xx도 실패로 집계**: 없는 경매를 조회하는 요청이 몰리면 건강한 서비스의 브레이커가 열려 정상 입찰까지 막힌다. 채택 안 함.
- **TimeLimiter와 Feign 타임아웃을 값만 맞춰 병행**: 기본 1초 트랩을 피해도 두 값이 나중에 어긋나기 쉽다. 채택 안 함.

## 결과

- 실측: bid-service 종료 시 Gateway 응답이 연결 실패 대기(약 2초)에서 브레이커 OPEN 후 14ms로 줄었다. 정산은 브레이커 OPEN 동안 멈췄다가 재기동 후 밀린 경매를 전부 처리했다.
- Resilience4j는 ignore한 예외에도 fallback을 호출하므로, bid-service의 FallbackFactory는 업무 예외를 **그대로 다시 던져야** 한다. 이 규칙을 깨면 404가 503으로 뭉개진다.
- 브레이커 이름을 Feign 클라이언트 이름으로 고정하는 `CircuitBreakerNameResolver` 빈이 필수가 됐다. 없으면 yml의 인스턴스 설정(ignore-exceptions 포함)이 조용히 적용되지 않는다.
- auction-service에 ErrorDecoder를 추가하면 4xx가 `FeignClientException`이 아닌 타입이 되어 ignore 목록에서 빠진다. 추가할 때 ignore 목록을 함께 바꿔야 한다.
- 장애 **첫 주기**에는 브레이커가 열리기 전이라 한 틱이 여전히 길 수 있다(최소 호출 5회 × 타임아웃). 모든 호출이 멱등이라 중복 실행돼도 데이터는 안전하다.
- D9에서 정산이 이벤트 기반으로 바뀌면 auction → payment 브레이커는 사라지고, Kafka 컨슈머의 재시도·DLQ가 그 역할을 이어받는다.
