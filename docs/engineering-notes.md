# 트랩과 비자명 메커니즘

## MySQL에는 조건부 UNIQUE 인덱스가 없다 — 생성 컬럼으로 흉내 낸다

"상품당 ACTIVE 경매 1개", "경매당 ACTIVE 입찰 1개"는 조건부 유니크(`WHERE status='ACTIVE'`)가 필요한데 MySQL 8.0은 지원하지 않는다. D21부터 생성 컬럼 + UNIQUE로 DB가 강제한다: `active_product_id = IF(status='ACTIVE', product_id, NULL)`(auction), `active_auction_id = IF(status='ACTIVE', auction_id, NULL)`(bid). UNIQUE 인덱스는 NULL끼리 충돌하지 않으므로 ACTIVE 행끼리만 충돌한다. 애플리케이션 사전 검사(`ensureNoActiveAuction`, 분산 락)는 빠른 거절과 명확한 오류를 위해 유지하고, 제약은 그 검사를 함께 통과한 동시 요청을 막는 최종 방어다. D21 재현(같은 상품 경매 두 개 동시 시작 100회)에서 **100회 모두** 두 요청이 사전 검사를 함께 통과했다 — "드문 경합"이 아니었다.

- 생성 컬럼은 엔티티에 매핑하지 않는다. INSERT/UPDATE 컬럼 목록에 들어가면 MySQL이 거부한다.
- 위반은 즉시 flush해 그 자리에서 받는다(`saveAndFlush`). 커밋 시점까지 미루면 트랜잭션 프록시 밖에서 `DataIntegrityViolationException`이 나가 500이 된다. 어떤 제약인지는 원인 메시지의 인덱스 이름(`Duplicate entry '5' for key 'auction.uk_auction_active_product'`)으로 가리고, 다른 무결성 위반은 바꾸지 않고 다시 던진다.
- 제약이 막으면 WARN 로그("…DB 제약이 막음")를 남긴다. 사전 검사를 통과한 경합이 얼마나 자주 생기는지 운영에서 볼 수 있다.

## 새 ACTIVE 입찰 INSERT 전에 이전 입찰의 OUTBID UPDATE를 flush해야 한다

`Bid`의 id는 `IDENTITY`라 `save(new Bid)` 순간 INSERT가 나가지만, 이전 ACTIVE를 OUTBID로 바꾼 UPDATE는 flush 때까지 미뤄진다. 그대로 두면 INSERT 시점에 잠시 ACTIVE가 2개라 `uk_bid_active_auction`에 걸린다 — 모든 정상 입찰이 실패한다. `placeBid`는 `saveAndFlush(previous)` 후 새 입찰을 저장한다. ACTIVE를 만드는 경로를 새로 추가할 때도 "ACTIVE를 내리는 쓰기 → flush → ACTIVE를 만드는 쓰기" 순서를 지킨다. (Hibernate는 flush 때도 INSERT를 UPDATE보다 먼저 실행하므로, 같은 flush에 몰아도 같은 문제가 난다.)

## Docker Compose MySQL 초기화 순서

`docker-entrypoint-initdb.d` 디렉토리의 파일은 알파벳 순서로 실행된다. `infra/mysql/init/` 디렉토리에 번호 접두사(01-, 02-, ...)를 붙여 순서를 보장한다. 01에서 DB를 생성하고, 02-04에서 각 DB의 스키마를 적용한다. 순서가 바뀌면 USE 문이 존재하지 않는 DB를 참조하여 실패한다.

## Windows 체크아웃에서 MySQL init 스크립트가 죽는다 (CRLF)

증상: `docker compose up` 후 MySQL 컨테이너가 `/docker-entrypoint-initdb.d/01-init-databases.sh: /bin/bash^M: bad interpreter`로 종료되고 DB가 하나도 만들어지지 않는다. 원인: Windows Git의 `core.autocrlf`가 `.sh`를 CRLF로 체크아웃하고, 컨테이너(Linux)는 shebang 줄 끝의 `\r`을 인터프리터 경로의 일부로 읽는다. 대응: `.gitattributes`의 `*.sh text eol=lf`가 이를 막는다. 이미 CRLF로 받은 파일은 `git rm --cached` 후 다시 체크아웃하거나 `git add --renormalize`로 고친다. 확인: `file infra/mysql/init/01-init-databases.sh`에 "with CRLF line terminators"가 없어야 한다.

## 스키마 변경 후에는 볼륨을 지워야 반영된다

초기화 스크립트는 MySQL 데이터 볼륨이 비어 있을 때만 실행된다. `schema.sql`/`infra/mysql/init/`을 바꿔도 `docker compose up`만 하면 기존 볼륨의 옛 스키마가 그대로 남아 JPA가 없는 컬럼(예: `bid.created_at`, `payment.failure_reason`, `shedlock` 테이블)을 참조하며 SQL 오류를 낸다. `docker compose down -v && docker compose up -d`로 재생성해야 한다. 데이터는 전부 사라진다.

## 로컬 3306 포트 충돌 (Windows MySQL80 서비스)

증상: `docker compose up`에서 `Ports are not available: ... 3306`. 원인: Windows에 설치된 MySQL80 서비스가 3306을 점유. 대응: 서비스를 잠시 멈추거나(`Stop-Service MySQL80`, 관리자 권한), 컨테이너 포트를 3307로 바꾸고 서비스 기동 시 `SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3307/...` 환경 변수로 덮어쓴다. 세 서비스의 `application.yml`은 3306을 기본값으로 둔다.

## Gateway와 서블릿 의존성 충돌

Spring Cloud Gateway는 Reactive(Netty) 기반이다. spring-boot-starter-web(서블릿)을 Gateway 모듈에 추가하면 WebFlux와 충돌하여 기동 실패한다. Gateway에는 spring-cloud-starter-gateway만 추가하고, web-application-type을 reactive로 설정한다.

## outbox 테이블의 현재 상태

outbox 테이블은 D1에서 스키마만 생성했다. 실제 Outbox 패턴 구현은 D11부터. 스키마를 미리 만들어 둔 이유는 나중에 마이그레이션 없이 바로 사용하기 위해서다.

## 서비스별 schema.sql과 Docker init SQL의 관계

각 서비스의 `src/main/resources/schema.sql`은 해당 서비스의 DDL 원본이다. `infra/mysql/init/` 디렉토리의 SQL 파일은 이 원본에 `USE {db_name};` 문을 추가한 사본이다. 스키마를 수정하면 양쪽을 동기화해야 한다.

## `@Transactional`은 자기 호출(self-invocation)에 걸리지 않는다

트랜잭션은 프록시를 거쳐 들어오는 호출에만 적용된다. 같은 빈 안에서 `this.method()`로 `@Transactional` 메서드를 부르면 트랜잭션이 열리지 않는데, 컴파일도 실행도 정상이라 알아채기 어렵다. 이 프로젝트에서는 스케줄러 빈(`AuctionSettlementScheduler`)이 트랜잭션 메서드를 별도 빈(`AuctionSettlementService`)에 두고, 결제 파사드(`PaymentService`)가 트랜잭션 메서드를 `PaymentTransactionService`에 둔 이유가 이것이다. 새 스케줄러나 파사드를 만들 때 같은 구조를 따른다.

## 다건 순회 경로에서 Feign 호출은 DB 트랜잭션 밖에서

정산 스케줄러처럼 여러 경매를 순회하는 경로에서 트랜잭션 안에서 원격 호출을 하면 커넥션을 원격 응답 시간(최대 읽기 타임아웃 5초)만큼 점유해 풀이 고갈되고, 원격은 성공했는데 커밋이 실패하면 두 서비스 상태가 어긋난다. 정산 스케줄러는 "Feign 호출 → 결과를 짧은 트랜잭션으로 저장" 순서를 지키며, 경매 1건 = 트랜잭션 1개다. 확인: 스케줄러 빈에 `@Transactional`이 없어야 하고, `AuctionSettlementService`의 메서드는 Feign 클라이언트를 주입받지 않아야 한다. 요청 1건당 조회 1회인 API 경로도 예외가 아니다(D20). 입찰 접수는 원래 경매 조회를 `@Transactional` 안에서 했는데, 부하 테스트에서 auction-service 응답이 5ms → 48ms로 느려지자 커넥션 보유 시간이 그만큼 늘어 bid-service 풀(10개)이 먼저 고갈됐다(대기 최대 29건, p95 692ms). 지금은 `BidService.prepareBid`(조회·검사, 락·트랜잭션 밖) → 분산 락 → `BidService.placeBid`(짧은 트랜잭션) 순서다.

## Feign 연결 실패·타임아웃은 ErrorDecoder를 거치지 않는다

`ErrorDecoder`는 HTTP 응답이 있을 때만 호출된다. 연결 거부·타임아웃은 응답이 없으므로 `feign.RetryableException`(FeignException의 하위)으로 바로 올라온다. 디코더에서 503으로 바꿨다고 끝난 게 아니라, 호출부 쪽에서 한 번 더 받아야 한다 — bid-service에서는 `AuctionClientFallbackFactory`가 그 역할을 한다(브레이커 도입 전에는 `BidService.fetchAuction`의 catch였다). 빠뜨리면 상류가 꺼져 있을 때 500 `INTERNAL_ERROR`가 나간다. Feign Retryer는 기본값(재시도 없음)을 유지한다 — 정산은 스케줄러 주기가 재시도이고, 이중 재시도는 한 틱의 최악 실행 시간을 늘려 ShedLock 만료와 충돌한다.

## ShedLock: lockAtMostFor보다 한 틱이 오래 걸리면 중복 실행된다

`lockAtMostFor`(PT30S, `SchedulerConfig`)는 락을 잡은 인스턴스가 죽었을 때 락이 풀리는 시간이면서, 정상 실행이 이 시간을 넘어도 락이 풀리는 시간이다. 정산 B단계는 틱당 최대 50건(`scheduler.auction.settlement-batch-size`) × Feign 읽기 타임아웃 합(bid 5s + payment 5s)이라 다운스트림이 전부 타임아웃 나면 최악 500초가 되어 30초를 넘는다. 그 상황에서는 배치 상한을 줄이거나 lockAtMostFor를 늘려야 한다. 중복 실행돼도 낙찰 확정·결제 요청이 멱등이라 데이터는 깨지지 않지만 로그가 중복된다. 락 만료 판정은 `usingDbTime()`으로 DB 시계를 쓴다 — 인스턴스 시계 차이가 있어도 락이 이상하게 풀리지 않는다.

## "입찰 없음"을 HTTP 404로 표현하면 경매가 잘못 유찰된다

낙찰 확정 API는 입찰이 없을 때 200 + `hasBids=false`를 돌려준다. 404를 유찰 신호로 해석하면 경로 오타·배포 불일치·라우팅 오류 같은 인프라 404가 경매를 FAILED(불가역)로 만든다. auction-service는 비 2xx를 전부 "다음 주기 재시도"로 다루고, 유찰 판정은 오직 `hasBids=false`에서만 한다. 이 API를 바꿀 때 이 규약을 깨면 안 된다.

## 정산 진행 단계는 status + winner_id 조합으로 읽는다 (D3 임시)

별도 정산 상태 컬럼이 없다. `CLOSED + winner_id NULL` = 정산 대기(스케줄러 B단계 대상), `CLOSED + winner_id 있음` = 낙찰자 확정됐으나 결제 실패(D10 승계 대기, B단계 대상에서 빠짐), `COMPLETED` = 결제 성공, `FAILED + winner_id NULL` = 유찰. 낙찰자는 결제 결과와 함께만 저장되므로 결제 완료 전에는 조회 API에서 `winnerId`가 null이다. D9에서 AuctionWon 이벤트를 발행하려면 낙찰자를 먼저 확정해야 하므로 그때 이 인코딩은 바뀐다. `Auction.assignWinner()`는 CLOSED에서만 허용된다 — 재시도 경로가 COMPLETED 경매를 덮어쓰는 것을 막는다.

## UNIQUE 위반 후 같은 트랜잭션에서 재조회하면 UnexpectedRollbackException

payment-service에서 같은 멱등키로 동시 INSERT가 나면 한쪽이 `DataIntegrityViolationException`을 받는다. 이 예외가 나면 현재 트랜잭션이 rollback-only로 표시되어, 같은 트랜잭션 안에서 기존 건을 재조회해 정상 반환해도 커밋 시점에 실패한다. 재조회는 트랜잭션이 끝난 뒤(비트랜잭션 파사드 `PaymentService`)에서 한다. `saveAndFlush`를 쓰는 이유도 같다 — 그냥 `save`면 INSERT가 커밋까지 미뤄져 UNIQUE 위반이 호출 시점에 드러나지 않는다.

## 활성화 애너테이션은 Application 클래스가 아니라 config 패키지에

`@EnableFeignClients`, `@EnableScheduling`, `@EnableSchedulerLock`은 auction·bid-service의 `config` 패키지(`FeignConfig`, `SchedulerConfig`)에 있다(payment-service는 Feign·스케줄러가 없어 config 패키지가 없다). Application 클래스에 두면 컨트롤러 슬라이스 테스트나 Mockito 단위 테스트가 Feign 빈·LockProvider(DataSource)를 요구하게 되어 "DB 없이 빌드 통과" 게이트가 깨진다.

## Git Bash curl로 한글 JSON 본문을 보내면 400이 난다

Windows Git Bash에서 `curl -d '{"name":"한글"}'`로 보내면 서버가 `요청 본문을 해석할 수 없습니다`(400)를 돌려준다. 원인은 서버가 아니라 클라이언트: Windows curl이 인자를 ANSI 코드페이지로 변환해 잘못된 UTF-8이 전송된다. UTF-8 파일로 저장해 `--data-binary @file`로 보내면 정상 처리된다. 수동 테스트에서 이 증상을 서버 버그로 오해하지 말 것.

## Spring Boot 패치 버전과 Spring Cloud Gateway 불일치 (NoSuchMethodError)

증상: Gateway는 정상 기동하고 자체 엔드포인트(`/auth/token`, actuator)도 되는데, 하류로 라우팅되는 모든 요청이 실패하고 로그에 `NoSuchMethodError: HttpHeaders.headerSet()`이 찍힌다. 원인: Spring Cloud 2023.0.4의 Gateway(4.1.6)는 Spring Framework 6.1.15+의 메서드를 호출하는데 Spring Boot 3.3.5는 6.1.14를 쓴다. 대응: Spring Boot를 3.3.13으로 올렸다. 단위·컨텍스트 테스트는 라우팅 필터 체인을 타지 않아 이 문제를 잡지 못한다 — Spring Boot·Cloud 버전을 바꾸면 반드시 Gateway 경유 요청을 실제로 보내 본다.

## 브레이커를 켜면 TimeLimiter(기본 1초)가 같이 붙는다

Spring Cloud CircuitBreaker는 브레이커마다 TimeLimiter를 함께 적용하고 기본값이 1초다. 아무 설정 없이 Feign 브레이커를 켜면 Feign 읽기 타임아웃(5초)보다 먼저 1초에 호출이 잘리고, 잘린 호출은 실패로 집계되어 브레이커가 열린다. 서비스 모듈은 `spring.cloud.circuitbreaker.resilience4j.disableTimeLimiter: true`로 끄고 Feign 타임아웃만 쓴다. Gateway는 `resilience4j.timelimiter.configs.default.timeout-duration: 8s` — 입찰 요청은 bid-service 안에서 auction-service를 최대 7초쯤 기다리므로 그보다 짧으면 정상 입찰이 타임아웃 실패로 바뀐다.

## Resilience4j는 ignore한 예외에도 fallback을 호출한다

`ignore-exceptions`는 "실패율 집계에서 제외"일 뿐 fallback 호출을 막지 않는다. bid-service의 `AuctionClientFallbackFactory`가 원인 예외를 보고 업무 예외(`AuctionNotFoundException` 404, `UpstreamErrorException` 502)를 **그대로 다시 던지는** 이유다. 이 분기를 없애면 없는 경매 입찰이 404가 아니라 503으로 나간다. 새 업무 예외를 ErrorDecoder에 추가하면 FallbackFactory의 rethrow 목록과 yml의 ignore 목록 두 곳을 함께 고친다.

## Feign 브레이커 기본 이름은 메서드 시그니처다

기본 이름은 `AuctionClient#getAuction(Long)` 형태라 yml의 `instances.auction-service`와 매칭되지 않는다. 오류도 경고도 없이 기본 설정(ignore 없음)으로 동작한다. `CircuitBreakerNameResolver` 빈이 이름을 Feign 클라이언트 이름으로 바꾼다. 확인: `/actuator/circuitbreakers`에 서비스 이름이 그대로 보여야 한다. 관련 메커니즘: Spring Cloud 팩토리 자체는 `configs.{이름}` → `configs.default`만 보지만, resilience4j 스타터가 yml의 `instances.{이름}`을 같은 레지스트리에 미리 만들어 두기 때문에 이름이 같으면 그 인스턴스가 쓰인다. Gateway(reactive)는 튜닝 값을 `configs.default`에 둔다.

## fallback이 없는 Feign 브레이커는 예외를 감싸서 던진다

auction-service의 BidClient·PaymentClient는 fallback이 없어서 실패가 `NoFallbackAvailableException`(원인: FeignException 또는 `CallNotPermittedException`)으로 올라온다. `catch (FeignException)`만으로는 잡히지 않는다. 정산 스케줄러는 클라이언트 호출을 `callExternal(...)`로 감싸 `ExternalCallFailedException`으로 바꾸고, 원인 사슬에서 `CallNotPermittedException`을 찾으면 그 주기의 남은 정산을 중단한다(로그 한 줄). 그 외 외부 실패는 저장 없이 다음 경매로. 이 구분이 없으면 브레이커 OPEN 동안 50건이 각각 스택트레이스를 찍는다.

## 장애 첫 주기는 여전히 ShedLock 락 시간을 넘을 수 있다

브레이커는 최소 호출 5회가 실패해야 열린다. 다운스트림이 "응답 없이 타임아웃"하는 방식으로 죽으면 첫 주기에 5회 × 읽기 타임아웃 5초 = 25초 이상이 걸리고, bid·payment가 동시에 죽으면 `lockAtMostFor`(30초)를 넘는다. 두 번째 주기부터는 OPEN이라 즉시 끝난다. 연결 거부(프로세스 종료)는 수 초 내 실패라 문제가 되지 않는다. 모든 호출이 멱등이라 중복 실행돼도 데이터는 안전하다.

## Gateway fallback 핸들러를 GET 전용으로 만들면 405가 난다

`fallbackUri: forward:/fallback/...`는 원래 요청의 HTTP 메서드를 유지한다. 핸들러가 `@GetMapping`이면 POST 입찰이 실패했을 때 503이 아니라 405가 나간다. `FallbackController`는 메서드 제한 없는 `@RequestMapping`이다.

## Eureka 전파 지연과 self-preservation

기본값(갱신 30초, 만료 90초, 조회 캐시 30초)에서는 서비스를 죽여도 1~2분간 죽은 인스턴스로 라우팅된다. 로컬용으로 클라이언트 lease 갱신 5초 / 만료 15초 / 레지스트리 조회 5초, 서버 eviction 5초·응답 캐시 5초·self-preservation 끔으로 줄였다. 서버와 클라이언트 설정은 짝이다 — 한쪽만 되돌리면 지연이 다시 길어진다. 그래도 종료 직후 수 초는 죽은 주소로 요청이 가며, 그 구간은 브레이커·fallback이 받는다. 기동 직후 첫 요청이 503인 것도 같은 이유(등록 전파 전)다.

## Eureka가 등록하는 IP가 가상 어댑터 주소일 수 있다

`prefer-ip-address: true`라 각 서비스는 호스트의 IP로 등록된다. VPN·가상 어댑터가 있는 PC에서는 그 어댑터의 주소(예: 100.x 대역)가 선택될 수 있다. 이 PC에서는 그 주소로도 로컬 호출이 되어 문제없었지만, 라우팅이 연결 실패로만 끝난다면 `GET :8761/eureka/apps`에서 등록된 `ipAddr`가 실제로 닿는 주소인지 먼저 확인한다. 필요하면 `eureka.instance.ip-address` 또는 `spring.cloud.inetutils.preferred-networks`로 고정한다.

## 8080 포트 충돌 (Oracle TNS 리스너)

증상: Gateway가 `Port 8080 was already in use`로 기동 실패. 이 PC에서는 Oracle의 `TNSLSNR`이 8080(XDB HTTP)을 쓰고 있다. 대응: `--server.port=8090`으로 띄운다. 문서의 8080 예시는 그 포트로 바꿔 읽는다.

## 원격 호출은 분산 락 안에도 두지 않는다 (임대 시간 초과)

Redisson `tryLock(wait, lease)`의 lease는 처리 중이어도 시간이 되면 락을 푼다. 입찰 락의 lease는 5초인데, D19까지는 락 안에서 auction-service를 조회해(connect 2초 + read 5초) 최악 7초가 걸릴 수 있었다. 상류가 느린 순간 락이 처리 도중 풀리고 다음 요청이 같은 경매에 들어온다. 기존 ACTIVE를 OUTBID로 바꾸는 경로는 `@Version`이 막지만, 첫 입찰 두 건의 동시 INSERT는 막지 못해 ACTIVE가 2개가 됐다(D21부터는 `uk_bid_active_auction`이 막는다). D20에서 조회를 락 밖으로 옮겨 락 안에는 DB 작업만 남았다. 락 안에 다시 원격 호출을 넣거나 lease를 줄일 때는 "락 안 최악 처리 시간 < lease"를 먼저 확인한다. 남은 경계: 락 안에서 Hikari 커넥션을 기다리는 시간(기본 connection-timeout 30초)은 lease보다 길 수 있다. 풀 고갈이 없는 한 문제되지 않는다.

## 락 밖에서 조회한 경매 상태는 락 대기 시간만큼 오래됐을 수 있다

조회를 락 앞으로 옮기면 그 값은 최대 락 대기(3초)만큼 오래된다. 마감 검사(`end_time > now`)를 락을 잡은 뒤 트랜잭션 안에서, 그 시점의 시각으로 하는 이유다. ACTIVE에서 바뀔 수 있는 상태는 CLOSED뿐이고 CLOSED는 end_time이 지나야 되므로, 이 검사가 오래된 status 값의 빈틈까지 막는다. 상태 전이(예: 일시정지 같은 ACTIVE → 다른 상태)를 추가하면 이 전제가 깨진다 — 그때는 락 안에서 상태를 다시 확인해야 한다.

## 부하 테스트 스택은 프로젝트 이름을 지정해야 뜬다

작업 폴더 이름에 한글이 있으면 `docker compose`가 프로젝트 이름을 만들지 못해 `project name must not be empty`로 실패한다. `infra/k6/compose.loadtest.yml`은 `name: auction-loadtest`를 지정해 이를 피하고, 개발용 볼륨과도 분리한다. 기본 compose만 쓸 때는 `-p <이름>`을 준다.

## 서비스 컨테이너 JVM은 UTC다

이미지(`eclipse-temurin:17-jre`)에 TZ 설정이 없어 컨테이너 안의 `LocalDateTime.now()`는 UTC다. 경매 `endTime`은 오프셋 없는 LocalDateTime이므로, 컨테이너 스택에 경매를 만들 때는 UTC 벽시계 시각을 보내야 한다(k6 `serverLocalDateTime()`). 호스트에서 직접 띄운 서비스(KST)와 섞어 쓰면 마감 시각이 9시간 어긋난다.

## Helm values.yaml에 비밀값을 두면 CD가 그대로 운영 키로 쓴다

D15~D19의 `values.yaml`에는 `secrets.jwtSecret`·`mysqlPassword` 기본값이 있었고, CD(`helm upgrade`)는 이 값을 덮어쓰지 않았다. 그대로 GKE에 배포되면 공개 저장소에 적힌 JWT 키로 운영 Gateway가 서명을 검증하므로, 누구나 임의의 userId로 토큰을 만들 수 있다. 지금은 기본값을 비우고 `required`로 막았으며, CD가 GitHub Secrets에서 주입한다. 로컬에서 `helm lint`/`helm template`을 돌릴 때도 `--set-string secrets.mysqlPassword=… --set-string secrets.jwtSecret=…`를 줘야 한다. 이미 공개된 키(`helm-local-secret-…`, `docker-local-secret-…`)는 운영에 쓰지 않는다.

