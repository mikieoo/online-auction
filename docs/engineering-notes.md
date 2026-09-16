# 트랩과 비자명 메커니즘

## MySQL 8.0 partial unique index 미지원

MySQL은 조건부 유니크 인덱스(partial unique index)를 지원하지 않는다. "하나의 Product에 ACTIVE Auction은 최대 1개"라는 불변 조건은 DB 레벨에서 강제할 수 없으므로, 경매 생성/시작 시 애플리케이션 코드에서 검증해야 한다. 이 검증을 빠뜨리면 동일 상품에 복수 ACTIVE 경매가 생길 수 있다. 같은 이유로 "한 경매에 ACTIVE Bid는 최대 1개"도 bid-service 코드가 지킨다(새 입찰 저장 전에 기존 ACTIVE를 OUTBID로 내린다).

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

정산 스케줄러처럼 여러 경매를 순회하는 경로에서 트랜잭션 안에서 원격 호출을 하면 커넥션을 원격 응답 시간(최대 읽기 타임아웃 5초)만큼 점유해 풀이 고갈되고, 원격은 성공했는데 커밋이 실패하면 두 서비스 상태가 어긋난다. 정산 스케줄러는 "Feign 호출 → 결과를 짧은 트랜잭션으로 저장" 순서를 지키며, 경매 1건 = 트랜잭션 1개다. 확인: 스케줄러 빈에 `@Transactional`이 없어야 하고, `AuctionSettlementService`의 메서드는 Feign 클라이언트를 주입받지 않아야 한다. 예외: bid-service `BidService.placeBid`는 요청 1건당 경매 조회 1회를 `@Transactional` 안에서 한다 — 쓰기 전 검증에 그 값이 필요하고 순회가 없어 커넥션 점유가 요청 1건 범위로 끝나므로 의도적으로 허용했다. D7 분산 락 도입 때 락 → 조회 → 트랜잭션 쓰기 순서로 재구성한다.

## Feign 연결 실패·타임아웃은 ErrorDecoder를 거치지 않는다

`ErrorDecoder`는 HTTP 응답이 있을 때만 호출된다. 연결 거부·타임아웃은 응답이 없으므로 `feign.RetryableException`(FeignException의 하위)으로 바로 올라온다. 디코더에서 503으로 바꿨다고 끝난 게 아니라, 호출부에서 `FeignException`을 한 번 더 잡아야 한다(bid-service `BidService.fetchAuction`). 빠뜨리면 상류가 꺼져 있을 때 500 `INTERNAL_ERROR`가 나간다. Feign Retryer는 기본값(재시도 없음)을 유지한다 — 정산은 스케줄러 주기가 재시도이고, 이중 재시도는 한 틱의 최악 실행 시간을 늘려 ShedLock 만료와 충돌한다.

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
