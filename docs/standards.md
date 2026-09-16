# 코드 규칙과 검증 게이트

## 검증 게이트

- 커밋 전 `./gradlew clean build` 통과 필수. exit 0이 아니면 커밋하지 않는다.
- 이 빌드는 **Docker·DB 없이** 통과해야 한다. DataSource를 띄우는 `@SpringBootTest`를 테스트에 넣으면 위반이다(로컬 MySQL이 없는 환경에서 빌드가 깨진다).
- 서비스 실행 흐름을 바꾼 경우(스케줄러, 서비스 간 호출, 스키마) 세 서비스를 실제로 띄워 대표 시나리오(입찰 → 마감 → 낙찰 → 결제)를 한 번 돌려 본다. 단위 테스트만으로는 Feign 배선·ShedLock 테이블·스키마 불일치를 잡지 못한다.

## 모듈 경계

- common 모듈에는 이벤트 DTO만 포함. 엔티티, 리포지토리, 서비스 로직은 서비스 모듈에만 존재.
- 서비스 모듈은 다른 서비스 모듈을 직접 의존하지 않는다. common만 의존.
- 서비스 간 호출의 요청·응답 DTO(Feign 클라이언트용)는 **호출하는 쪽 서비스가 자체 정의**한다. common에 넣지 않는다. 응답 DTO에는 `@JsonIgnoreProperties(ignoreUnknown = true)`를 붙여 상대 서비스가 필드를 추가해도 깨지지 않게 한다.
- 각 서비스는 자기 DB만 접근한다. 다른 서비스의 데이터는 REST(Feign) 또는 이벤트로만 얻는다.
- Gateway는 Spring Cloud Gateway(Reactive/Netty) 기반. 서블릿 의존성을 추가하지 않는다.

## 패키지 구조

`com.auction.{서비스명}.{계층}` — 계층: `controller`, `service`, `domain`, `repository`, `dto`, `exception`, `client`(Feign 클라이언트와 그 DTO), `config`(활성화 애너테이션·빈 설정), `scheduler`, `event`.

- `@EnableFeignClients`, `@EnableScheduling`, `@EnableSchedulerLock` 같은 활성화 애너테이션은 Application 클래스가 아니라 `config` 패키지의 설정 클래스에 둔다.
- `@Transactional` 메서드를 같은 클래스 안에서 호출하지 않는다(프록시 미적용). 스케줄러·파사드는 트랜잭션 메서드를 별도 빈에 둔다.
- 다건을 순회하는 경로(스케줄러·배치)에서는 Feign 호출을 `@Transactional` 메서드 안에서 하지 않는다 — 경매 1건 = 트랜잭션 1개, 원격 호출은 그 사이에서. 요청 1건당 조회 1회인 API 경로(예: 입찰 접수의 경매 조회)는 쓰기 전 검증 값이 필요하므로 한 트랜잭션에 두는 것을 허용한다.

## REST 인터페이스 규칙

- 공개 API 경로는 `/api/v1/{복수 리소스}`, 서비스 간 내부 API는 `/internal/v1/...`. 내부 API 컨트롤러는 클래스명에 `Internal`을 붙인다(예: `InternalBidController`).
- 사용자 식별은 `X-User-Id` 헤더(`@RequestHeader`). 요청 본문에 사용자 id를 넣지 않는다.
- 요청 DTO는 bean validation(`@NotNull`, `@Positive`, `@NotBlank` …)을 붙이고 컨트롤러에서 `@Valid`로 검증한다. 서비스 계층의 도메인 검증은 별개로 유지한다.
- 예외는 서비스별 도메인 예외 클래스(HTTP 상태·`code`를 가진 예외)로 던지고, 서비스별 `@RestControllerAdvice` 하나가 전부 매핑한다. 컨트롤러에서 `try/catch`로 상태 코드를 만들지 않는다. 원시 `IllegalArgumentException`/`IllegalStateException`을 새로 던지지 않는다(도메인 엔티티의 상태 가드 `IllegalStateException`은 예외 — 처리기가 409로 매핑한다).
- 오류 본문 형식(`{code, message}`)과 code 집합의 단일 원본은 외부 인터페이스 계약 문서다. 새 code를 만들면 그 문서에 추가한다.
- "결과 없음"이 호출자의 비가역 결정(예: 유찰)으로 이어지는 내부 API는 HTTP 404가 아니라 200 + 명시적 본문 필드로 표현한다.

## 버전 고정

| 항목 | 버전 |
|------|------|
| Java | 17 (sourceCompatibility) |
| Spring Boot | 3.3.5 |
| Spring Cloud | 2023.0.4 (OpenFeign 포함) |
| ShedLock | 5.16.0 |
| MySQL | 8.0 |
| Gradle | 8.10 (wrapper), 빌드 캐시·병렬 빌드 활성화(`gradle.properties`) |

## 네이밍

- 이벤트 DTO: `{동작}Event` (예: AuctionStartedEvent). 공통 필드: eventId(String), occurredAt(LocalDateTime).
- 테이블명: snake_case 단수형. PK: `{테이블명}_id`. 생성 시각 컬럼은 `created_at`.
- 엔티티 필드는 `@Column(name = "...")`으로 snake_case 컬럼명을 명시한다(암묵 매핑에 의존해 컬럼명이 어긋난 사고가 있었다).
- 오류 code: 대문자 스네이크 케이스(`AUCTION_NOT_ACTIVE`).
- 커밋 메시지 규칙은 아래 "커밋" 절 참고.

## 커밋

- 작성자(author)·커미터(committer)는 개인 GitHub 계정 identity(`miki`, 개인 이메일)로 남긴다. 회사 이메일이나 실명 identity로 커밋하지 않는다 — 공개 포트폴리오 저장소이므로 커밋 전 `git config user.name`/`user.email`을 확인한다.
- `Co-Authored-By:` 트레일러(특히 AI 도구가 자동으로 붙이는 `Co-Authored-By: Claude …`)를 **절대 넣지 않는다**. 도구 자동 첨부는 프로젝트 설정 `.claude/settings.json`(`attribution.commit`/`attribution.pr` = 빈 문자열, `includeCoAuthoredBy: false`)으로 꺼져 있다. 이 파일은 커밋 대상이다. 커밋 후 `git log -1 --format=%B`로 트레일러가 없는지 확인한다.
- 제목: `<type>: <한국어 요약>` — type은 `feat`, `fix`, `docs`, `chore`, `refactor`, `test` 중 하나(영문 소문자, 콜론 뒤 한 칸). 요약은 한국어, 50자 안팎, 마침표 없음.
- 본문(선택): 한국어. 무엇을 왜 바꿨는지. "어떻게"는 코드가 말하므로 쓰지 않는다.
- 브랜치: 작업 브랜치는 `feature/<기능명>` (영문 소문자·하이픈). 날짜나 일차(D3 등)를 브랜치 이름에 쓰지 않는다. 기본 브랜치는 `main`.
- `./gradlew clean build` exit 0이 아니면 커밋하지 않는다.

## DB

- JPA ddl-auto: none. 스키마는 SQL 파일로 관리.
- 스키마 원본은 각 서비스의 `src/main/resources/schema.sql`, `infra/mysql/init/0N-*.sql`은 `USE` 문을 붙인 사본. 둘을 함께 수정한다.
- ACTIVE Auction 유일성(상품당 1개), ACTIVE Bid 유일성(경매당 1개)은 애플리케이션 레벨에서 검증 (MySQL은 partial unique index 미지원).
- 경매 정산 진행 단계를 나타내는 별도 컬럼은 두지 않는다(D3 결정 — `status + winner_id` 조합으로 표현, D9에서 재설계).

## 설정

- 서비스 간 호출 대상 주소는 `clients.{서비스명}.url`, 타임아웃은 `clients.{서비스명}.connect-timeout-ms` / `read-timeout-ms`. D4에서 Eureka로 전환하기 전까지 고정 URL.
- 스케줄러 주기·배치 상한은 `scheduler.{이름}.*`. 결제 시뮬레이션 규칙은 `payment.simulation.*`. 입찰 증가 단위는 `bid.increment`.
- 셸 스크립트(`*.sh`)는 `.gitattributes`로 LF 고정. CRLF로 커밋하면 컨테이너에서 실행되지 않는다.

## 테스트

- 핵심 비즈니스 로직(입찰 검증, 정산 분기, 결제 멱등성, 상태 전이) 우선 커버. JUnit 5 + Mockito 단위 테스트. 컨트롤러·예외 처리기는 `MockMvcBuilders.standaloneSetup(...)`로 검증한다.
- Testcontainers 통합 테스트는 아직 도입하지 않았다(빌드가 Docker에 의존하지 않게 하려는 결정). 도입 시 별도 Gradle 태스크로 분리해 `clean build`의 Docker 무의존성을 유지한다.
