# D4: Gateway + Eureka + Circuit Breaker — 입구를 하나로, 장애는 번지지 않게

## 오늘 한 일

클라이언트가 세 서비스의 포트를 직접 두드리던 구조를 **Gateway(8080) 하나**로 모았다. Gateway는 JWT를 검증해 `X-User-Id`를 붙여 주고, 서비스 위치는 **Eureka**(새 모듈 `discovery-service`)에서 이름으로 찾는다. 그리고 Gateway 라우트와 서비스 간 Feign 호출 양쪽에 **Resilience4j Circuit Breaker**를 걸어, 한 서비스가 죽어도 나머지가 타임아웃에 끌려 들어가지 않게 했다.

```
클라이언트 ─(JWT)→ Gateway ─lb://→ auction / bid / payment ─Feign(lb + breaker)→ 서로
                      └── Eureka(discovery-service) ← 전 서비스 등록
```

마지막에는 실제로 bid-service를 죽여서 브레이커가 열리고(OPEN), 다시 살렸을 때 밀린 경매가 정산되는 것까지 확인했다.

---

## 1. Gateway — 입구가 하나여야 하는 이유

### `X-User-Id`는 Gateway만 쓸 수 있다

D3까지는 클라이언트가 `X-User-Id: 1`을 직접 넣었다. 누구든 남의 id를 넣으면 그 사람이 된다. D4부터는:

```java
public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    ServerHttpRequest request = exchange.getRequest();
    if (!requiresAuthentication(request)) {
        // 공개 조회는 토큰이 있어도 검증하지 않는다 — 만료된 토큰을 들고 있다는 이유로 공개 조회가 막히면 안 된다.
        return chain.filter(withUserId(exchange, null));   // 그래도 X-User-Id는 지운다
    }
    ...
    userId = jwtTokenService.parseUserId(token);
    return chain.filter(withUserId(exchange, userId));     // 클라이언트 값은 버리고 sub로 교체
}
```

핵심은 `withUserId`가 **항상** 들어온 `X-User-Id`를 지운다는 것이다. 공개 GET에서도 지운다. 검증은 "토큰(사용자 2) + 위조 헤더 `X-User-Id: 1`"로 판매자 1의 경매에 입찰해 본 것 — 서버는 사용자 2로 처리해 입찰이 성공했다(판매자 본인이었다면 403이었을 것).

인증 규칙은 단순하게 잡았다: `/api/**`의 **GET이 아닌 모든 요청**과 **결제 조회**는 토큰 필수, 상품·경매·입찰 조회는 공개. 실패는 전부 401이고 code만 `UNAUTHORIZED` / `TOKEN_EXPIRED`로 나눈다. 서명이 틀렸는지 형식이 틀렸는지는 구분해 알려주지 않는다 — 공격자에게 힌트가 되기 때문이다.

### JWT 세부에서 배운 것

- **서명 키에 기본값을 두지 않았다.** `JWT_SECRET`이 없거나 32바이트 미만이면 Gateway가 **기동에 실패**한다. 설정 파일에 개발용 키를 넣어 두면 언젠가 그 키로 운영에 올라간다.
- **HS256을 고정했다.** jjwt의 `Keys.hmacShaKeyFor`는 키 길이에 따라 HS384/HS512를 고르고, 파서는 토큰 헤더의 `alg`를 따른다. `SecretKeySpec(..., "HmacSHA256")`으로 키를 만들고, 검증된 토큰이라도 `alg != HS256`이면 거절한다.
- `sub`는 `[1-9][0-9]*` 형태만 받는다. `"07"`, `"+7"`, `"7.5"`는 401.

### 토큰은 누가 발급하나

이 프로젝트에는 로그인 서비스가 없다. 그래서 Gateway에 `POST /auth/token {userId}`를 두되 **`local` 프로필에서만 빈이 생기게** 했다(`@Profile("local")`). 비밀번호 없이 아무 id로나 토큰을 주는 API라서 운영 프로필에 존재하면 안 된다. 다른 프로필에서는 경로 자체가 404다. GKE에 올릴 때 토큰을 어떻게 발급할지는 아직 정하지 않았고, 미해결 문제로 적어 두었다.

### `/internal/**`는 밖에서 안 보인다

D3에서 낙찰 확정·결제 생성을 `/internal/v1/...`로 분리해 둔 덕에, Gateway는 `/api/**`만 라우팅하면 끝이다. `/internal/**`는 라우트가 없어 404 `NOT_FOUND`. WebFlux 기본 오류 본문이 새지 않도록 커스텀 `ErrorWebExceptionHandler`로 Gateway가 만드는 오류도 전부 `{code, message}` 형식으로 맞췄다.

---

## 2. Eureka — 주소 대신 이름

```yaml
# gateway
routes:
  - id: bid-service
    uri: lb://bid-service          # 이름으로 찾고, LoadBalancer가 인스턴스를 고른다
```

```java
@FeignClient(name = "auction-service", url = "${clients.auction-service.url:}", fallbackFactory = ...)
```

`url`이 비어 있으면 Eureka + Spring Cloud LoadBalancer로 찾는다. `clients.*.url`을 **선택적 오버라이드로 남긴 이유**: discovery 없이 서비스 하나만 띄워 개발할 때, 그리고 D18에서 K8s Service DNS로 바꿀 때 쓸 수 있다.

**전파 지연이 있다.** Eureka는 기본값으로 갱신 30초, 만료 90초, 조회 캐시 30초라서 서비스를 죽여도 1분 넘게 죽은 인스턴스로 라우팅된다. 로컬용으로 갱신 5초 / 만료 15초 / 조회 5초로 줄이고, 서버의 self-preservation을 껐다. self-preservation은 "갱신이 갑자기 줄면 네트워크 문제로 보고 만료를 멈추는" 보호 장치인데, 서비스를 수시로 껐다 켜는 로컬에서는 죽은 인스턴스를 붙잡아 두는 원인이 된다(운영에서는 켜 두는 게 기본).

---

## 3. Circuit Breaker — 두 군데에 거는 이유

| 위치 | 하는 일 | fallback |
|---|---|---|
| Gateway 라우트 | 죽은 서비스로 가는 요청을 즉시 안내 응답으로 돌려준다 | 503 `SERVICE_UNAVAILABLE` |
| Feign (bid→auction) | 입찰이 죽은 auction-service를 5초씩 기다리지 않게 한다 | **빠른 503 거절** |
| Feign (auction→bid·payment) | 정산 스케줄러가 죽은 서비스를 50번씩 두드리지 않게 한다 | 없음(다음 주기 재시도) |

상태 전이: **CLOSED** →(최근 10번 중 최소 5번 호출, 실패율 50% 이상)→ **OPEN**(10초간 호출 자체를 안 함) → **HALF_OPEN**(3번만 시험) → 성공하면 CLOSED, 실패하면 다시 OPEN.

실측: bid-service를 죽이면 CLOSED 상태에서는 요청마다 약 2초(연결 실패) 걸리던 응답이, OPEN이 된 뒤에는 **14ms**로 돌아왔다.

### fallback은 "대체 값"이 아니라 "빠른 거절"이다

입찰에서 auction-service를 못 부르면 경매가 ACTIVE인지, 판매자가 누구인지, 시작가가 얼마인지 모른다. 그 상태로 입찰을 받으면 도메인 규칙이 전부 뚫린다. 그래서 fallback은 값을 지어내지 않고 503으로 거절한다.

### 업무상 4xx는 "장애"가 아니다

```java
BidException toBidException(Throwable cause, Long auctionId) {
    for (...) {   // 원인 사슬을 따라가며
        if (current instanceof AuctionNotFoundException      // 404
                || current instanceof UpstreamErrorException // 502
                || current instanceof UpstreamUnavailableException) {
            return (BidException) current;                   // ErrorDecoder가 만든 그 예외를 그대로
        }
        if (current instanceof CallNotPermittedException) {  // 브레이커 OPEN
            return new UpstreamUnavailableException(...);
        }
    }
    return new UpstreamUnavailableException(...);            // 연결 실패·타임아웃
}
```

두 가지 함정이 있었다.

1. **없는 경매를 조회하는 요청이 몰려도 auction-service는 건강하다.** 404를 실패로 세면 브레이커가 열려 정상 입찰까지 막힌다. `ignore-exceptions`에 업무 예외를 넣었다. (검증: 없는 경매로 입찰 8번 → 전부 404, 브레이커 `failedCalls=0`, CLOSED.)
2. **Resilience4j는 ignore한 예외에도 fallback을 호출한다.** fallback이 무조건 503을 던지면 404 `AUCTION_NOT_FOUND`가 전부 503으로 뭉개진다. 그래서 업무 예외는 **그대로 다시 던진다.**

Gateway 브레이커도 같은 생각이다. `statusCodes`를 설정하지 않아서, 하류가 **응답을 했다면**(4xx든 5xx든) 실패로 세지 않는다. bid-service가 "auction-service가 죽어서 503"이라고 정상 응답한 것 때문에 bid-service 브레이커가 열리면 안 된다.

### TimeLimiter 기본 1초 트랩

Spring Cloud CircuitBreaker는 브레이커에 **TimeLimiter(기본 1초)** 를 같이 붙인다. 아무 설정 없이 켜면 Feign 읽기 타임아웃을 5초로 잡아 놨어도 1초에 잘린다. 타임아웃 장치가 둘이면 값을 맞춰 두어도 언젠가 어긋나므로, 서비스 쪽은 `spring.cloud.circuitbreaker.resilience4j.disableTimeLimiter: true`로 끄고 Feign 타임아웃 하나에 맡겼다. Gateway는 TimeLimiter를 유지하되 8초로 올렸다 — 입찰 요청은 안에서 auction-service를 최대 7초쯤 기다릴 수 있다.

### 브레이커 이름

Feign 브레이커의 기본 이름은 `AuctionClient#getAuction(Long)` 같은 메서드 시그니처다. yml의 `instances.auction-service` 설정과 **조용히 매칭되지 않고**, actuator 화면에서도 읽기 어렵다. `CircuitBreakerNameResolver` 빈으로 이름을 Feign 클라이언트 이름으로 고정했다.

### Gateway fallback과 405

`fallbackUri: forward:/fallback/bid-service`는 **원래 HTTP 메서드를 유지한 채** 포워드한다. fallback 컨트롤러를 `@GetMapping`으로 만들면 POST 입찰이 실패했을 때 503이 아니라 405가 나간다. 메서드 제한 없는 `@RequestMapping`으로 받았다.

---

## 4. 스케줄러 × 브레이커 × ShedLock

D3에서 남겨 둔 걱정이 있었다: 정산 B단계는 틱당 최대 50건 × Feign 타임아웃이라, 다운스트림이 죽으면 한 틱이 ShedLock `lockAtMostFor`(30초)를 넘을 수 있다.

```java
} catch (ExternalCallFailedException e) {
    if (findCause(e, CallNotPermittedException.class) != null) {
        // 브레이커 OPEN이면 이번 틱의 남은 대상을 시도하지 않는다. 재시도는 스케줄러 주기가 담당한다.
        log.warn("... 이번 틱의 남은 정산을 중단하고 다음 주기에 재시도합니다. ... 미처리={}건", ...);
        return;
    }
    ...
}
```

브레이커가 열려 있으면 **그 틱의 남은 정산을 중단**하고 로그 한 줄만 남긴다. OPEN 상태에서 49건을 더 시도해 봐야 즉시 거절 로그만 쌓인다. 일찍 끝내면 최악 틱 시간도 줄어든다(다만 장애 **첫 틱**은 브레이커가 열리기 전이라 여전히 길 수 있다 — 호출이 전부 멱등이라 중복 실행돼도 데이터는 안전하다).

브레이커를 켜면 예외 모양도 바뀐다. fallback이 없는 Feign 브레이커는 실패를 `NoFallbackAvailableException`으로 감싸서 던진다. D3의 `catch (FeignException)`만으로는 못 잡는다. 그래서 클라이언트 호출 두 곳을 `callExternal(...)`로 감싸 "외부 호출 실패"와 "DB 저장 실패"를 타입으로 구분했다.

---

## 5. 버전 함정 하나

Gateway를 띄우고 첫 요청을 보내자 라우팅되는 **모든 요청이 실패**했다.

```
java.lang.NoSuchMethodError: 'java.util.Set org.springframework.http.HttpHeaders.headerSet()'
    at org.springframework.cloud.gateway.filter.headers.ForwardedHeadersFilter.filter(...)
```

Spring Cloud 2023.0.4의 Gateway(4.1.6)는 Spring Framework 6.1.15+에 있는 메서드를 부르는데, Spring Boot 3.3.5는 6.1.14를 쓴다. 단위 테스트와 컨텍스트 테스트는 전부 통과했다 — 실제 라우팅 경로를 타야만 드러나는 문제였다. Spring Boot를 3.3.13으로 올려 해결했다. **빌드가 초록이어도 실제로 띄워 봐야 한다**는 걸 D3에 이어 또 확인했다.

---

## 6. 검증

- 단위·컨텍스트 테스트 196개(auction 71, bid 61, payment 37, gateway 27). Docker·DB·Eureka 없이 `./gradlew clean build` 통과.
- 실기동(Eureka + 세 서비스 + Gateway):
  1. 토큰 발급 → 상품 → 경매 → 시작 → 입찰 201/201/400/403 → 마감 15초 뒤 `COMPLETED`, 결제 조회 200(본인)·403(타인)
  2. 무토큰 POST 401, 조작 토큰 401, 공개 GET 200, 무토큰 결제 조회 401
  3. 위조 `X-User-Id` 무시 확인
  4. `/internal/**`·없는 경로 404 `NOT_FOUND`
  5. 없는 경매 입찰 8회 → 404, 브레이커 CLOSED(failed 0)
  6. bid-service 종료 → Gateway 503 `SERVICE_UNAVAILABLE`, 브레이커 OPEN 후 응답 14ms, auction-service의 `bid-service` 브레이커 OPEN, 그 사이 마감된 경매는 `CLOSED + 낙찰자 없음` 유지
  7. bid-service 재기동 → HALF_OPEN → 밀린 경매 전부 `COMPLETED`

이 PC에서는 8080을 Oracle 리스너가 쓰고 있어서 Gateway를 8090으로 띄워 검증했다(`--server.port=8090`).

---

## 다음 할 일 (D5)

- gRPC 전환: auction ↔ bid 1구간(낙찰 확정), 입찰 스트리밍.
- 남은 숙제: GKE에서의 토큰 발급 수단, 서비스 직접 포트 차단(D18 ClusterIP).

---

## 면접 대비 포인트

**Q: API Gateway를 두는 이유는?**
→ 인증·라우팅·장애 대응 같은 공통 관심사를 한 곳에 모으기 위해서다. 내부 서비스는 토큰을 모르고 `X-User-Id`만 신뢰한다. 그 신뢰가 성립하려면 Gateway가 클라이언트가 보낸 `X-User-Id`를 반드시 지워야 하고, 서비스 포트가 외부에 노출되지 않아야 한다.

**Q: Circuit Breaker의 세 상태와 전이 조건은?**
→ CLOSED(정상, 실패율 집계) → 실패율이 임계값을 넘으면 OPEN(호출 차단, 즉시 실패) → 대기 시간 후 HALF_OPEN(제한된 시험 호출) → 성공하면 CLOSED, 실패하면 OPEN. 목적은 죽은 서비스를 기다리느라 호출자의 스레드·커넥션이 고갈되는 연쇄 장애를 막는 것.

**Q: fallback에는 무엇을 넣어야 하나?**
→ 도메인이 허용하는 것만. 캐시된 상품 목록처럼 "오래된 값이라도 의미 있는" 경우엔 대체 값이 맞지만, 입찰 검증처럼 최신 상태 없이는 판단할 수 없는 경우엔 값을 지어내면 안 되고 빠르게 거절해야 한다.

**Q: 어떤 예외를 브레이커 실패로 세야 하나?**
→ 상대가 "아픈" 신호만. 연결 실패·타임아웃·5xx는 실패, 404·400 같은 업무 응답은 상대가 건강하다는 증거이므로 제외한다. 그리고 Resilience4j는 ignore한 예외에도 fallback을 호출하므로, fallback에서 업무 예외를 그대로 다시 던져야 상태 코드가 보존된다.

**Q: Eureka의 self-preservation은 무엇이고 왜 껐나?**
→ 갱신(heartbeat)이 기대치 이하로 떨어지면 네트워크 분할로 보고 인스턴스 만료를 멈추는 보호 모드다. 운영에서는 대량 오탐 제거를 막아 주지만, 로컬에서는 죽인 서비스가 레지스트리에 남아 죽은 주소로 라우팅되는 원인이 된다.

**Q: Gateway 브레이커와 Feign 브레이커를 둘 다 두면 중복 아닌가?**
→ 보호 대상이 다르다. Gateway 브레이커는 외부 클라이언트의 대기 시간을, Feign 브레이커는 서비스 내부 자원(스레드, DB 커넥션, 스케줄러 틱 시간)을 보호한다. 하류가 HTTP 응답을 돌려준 경우는 Gateway에서 실패로 세지 않아서, 안쪽 장애 때문에 바깥 브레이커가 잘못 열리지 않게 했다.
