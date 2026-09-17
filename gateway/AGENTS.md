# gateway

## 범위

단일 공개 진입점(8080). `/api/**` 라우팅(`lb://` + Eureka), JWT 검증과 `X-User-Id` 주입, 라우트별 Circuit Breaker + fallback, `local` 프로필 전용 토큰 발급, Gateway가 만드는 오류의 표준 본문(`{code, message}`).

## 범위 밖

- 비즈니스 로직·소유권 기반 인가(판매자·결제자 본인 여부) → 각 서비스
- DB 없음
- 회원·로그인·비밀번호·리프레시 토큰

## 불변 조건

- Spring Cloud Gateway(Reactive/Netty) 기반. spring-boot-starter-web(서블릿)을 추가하면 기동 실패.
- 하류로 라우팅되는 모든 요청에서 클라이언트가 보낸 `X-User-Id`를 제거한다. 공개 GET도 예외 없음. 이 제거가 빠지면 내부 서비스의 헤더 신뢰가 무너진다.
- 인증 필요: 라우팅되는 GET이 아닌 모든 요청 + `GET /api/v1/payments/**`. 실패 시 필터에서 401을 직접 응답하고 체인을 호출하지 않는다. code는 `UNAUTHORIZED`(없음·형식·서명·alg·sub) / `TOKEN_EXPIRED` 두 가지뿐, 실패 사유를 더 나눠 알려주지 않는다.
- 서명 알고리즘은 HS256 고정. 키는 `SecretKeySpec(..., "HmacSHA256")`로 만들고 검증된 토큰이라도 헤더 `alg != HS256`이면 거절한다(jjwt는 키 길이에 따라 HS384/512를 고르고 파서는 헤더의 alg를 따르기 때문).
- `gateway.jwt.secret`(← `JWT_SECRET`)에 기본값을 두지 않는다. 없거나 32바이트 미만, 또는 ttl ≤ 0이면 빈 생성에서 예외 → 기동 실패.
- `/auth/token` 컨트롤러는 `@Profile("local")`. 프로필 조건을 없애면 비밀번호 없는 토큰 발급이 모든 환경에 열린다.
- `/internal/**`에는 라우트를 만들지 않는다. 미매칭 경로는 404 `NOT_FOUND`.
- 브레이커는 하류가 돌려준 HTTP 응답(4xx·5xx)을 실패로 세지 않는다 — 라우트 필터에 `statusCodes`를 넣지 않는다. 실패는 연결 실패와 타임아웃(TimeLimiter 8초)뿐.
- fallback 핸들러(`/fallback/{service}`)는 HTTP 메서드 제한 없는 `@RequestMapping`. forward는 원래 메서드를 유지하므로 GET 전용이면 POST 실패 시 405가 나간다.
- 하류 서비스의 오류 응답은 손대지 않고 전달한다. WebFlux 기본 오류 본문이 새지 않게 `GatewayErrorWebExceptionHandler`(`@Order(-2)`)가 Gateway 자체 오류를 표준 본문으로 바꾼다.

## 구현 패턴

- 패키지: `filter`(AuthenticationFilter — GlobalFilter, order -100), `security`(JwtTokenService), `controller`(TokenController, FallbackController), `config`(JwtProperties, GatewayConfig), `exception`(오류 핸들러·ErrorResponseWriter·토큰 예외), `dto`.
- 라우트·브레이커·TimeLimiter·Eureka·actuator 노출은 전부 `application.yml`. 브레이커 튜닝 값은 `resilience4j.circuitbreaker.configs.default` 아래에 둔다 — Spring Cloud CircuitBreaker는 `configs.{이름}` → `configs.default` 순으로 찾고 `instances.{이름}`은 보지 않는다. 서비스별로 다르게 하려면 `configs.bid-service`처럼 추가한다.
- 전역 필터는 라우트에 매칭된 요청에만 돈다. `/auth/token`·`/fallback/**`·`/actuator/**`는 Gateway 자체 엔드포인트라 인증 필터를 거치지 않는다.
- HEAD·OPTIONS도 "GET이 아닌 요청"이라 토큰이 필요하다. 브라우저 클라이언트(CORS preflight)를 붙이게 되면 이 규칙을 다시 봐야 한다.

## 테스트 가이드

- JwtTokenService: 유효 토큰 왕복, 만료 분류, 서명 불일치, alg 불일치, sub 형식, secret 누락·짧음·ttl ≤ 0 → 생성 실패
- AuthenticationFilter(`MockServerWebExchange` + 캡처 체인): X-User-Id 제거, 공개 GET 통과·헤더 미부착, 무토큰 401·체인 미호출, 결제 조회 401, 유효 토큰 시 sub 주입, 위조 헤더 교체
- FallbackController: POST에도 503 본문
- 컨텍스트 테스트 2개(`eureka.client.enabled=false`, 테스트 전용 secret은 테스트 코드 안에): `local` → `/auth/token` 200 / 프로필 없음 → 404 `NOT_FOUND`, `/internal/**` 404, actuator 노출 범위
- 컨텍스트 테스트는 실제 하류 라우팅 경로를 타지 않는다. 라우팅 필터 체인의 문제(예: 프레임워크 버전 불일치로 인한 NoSuchMethodError)는 서비스를 실제로 띄운 확인에서만 드러난다.
