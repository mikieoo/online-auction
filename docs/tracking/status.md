# 진행 상황

## 완료 (D1)

- Gradle 멀티 모듈 프로젝트: root + auction-service, bid-service, payment-service, gateway, common. `./gradlew clean build` 통과 확인.
- DB 스키마: auction_db(product, auction, outbox), bid_db(bid), payment_db(payment). Docker Compose 기동 후 3개 DB 테이블 생성 확인.
- Docker Compose: MySQL 8.0 단일 인스턴스 + 3개 DB. `infra/mysql/init/` 초기화 스크립트로 자동 생성.
- 공유 이벤트 DTO: 7개 이벤트 클래스(common 모듈). 빌드 통과 확인.

## 완료 (D2)

- Docker Compose 확장: Redis, Kafka(Zookeeper), Kafka UI, Zipkin 추가. 6개 컨테이너 전부 기동 확인.
- auction-service REST API: Product(등록/조회), Auction(생성/시작/조회) — JPA 엔티티, 리포지토리, 서비스, 컨트롤러. 상태 전이(WAITING→ACTIVE), 낙관적 락(@Version). API 런타임 테스트 완료.
- bid-service 뼈대: Bid 도메인(JPA), BidRepository.
- payment-service 뼈대: Payment 도메인(JPA, 멱등키), PaymentRepository.

## 완료 (D3)

- bid-service: 입찰 접수·검증 API(`POST /api/v1/bids`, 조회 2개), auction-service 동기 조회(OpenFeign + ErrorDecoder), 낙찰 확정 내부 API(`POST /internal/v1/bids/auctions/{id}/winner`, 멱등). Bid 상태 집합 `ACTIVE/OUTBID/WINNER`로 정리, `bid.created_at` 컬럼으로 통일.
- auction-service: 마감·정산 스케줄러(ShedLock, 5초 주기, 경매별 트랜잭션, Feign은 트랜잭션 밖), bid/payment Feign 클라이언트, `assignWinner` CLOSED 가드, 시작 시 endTime 경과 거절, `AuctionResponse`에 `sellerId`/`startingPrice` 추가, `shedlock` 테이블.
- payment-service: 멱등 결제 생성 내부 API(`POST /internal/v1/payments`), 금액 접미사(기본 `9999`) 기반 결정적 시뮬레이션, 결제 조회(본인만). Payment 상태 `REQUESTED/COMPLETED/FAILED`, `failure_reason` 컬럼 추가. UNIQUE 위반 시 트랜잭션 밖 재조회.
- 세 서비스 공통: `@RestControllerAdvice` + `{code, message}` 오류 본문(400/403/404/409/502/503), 요청 DTO bean validation.
- 검증 상태: 단위 테스트 151개(auction 64, bid 50, payment 37, Mockito) 통과. Docker 없이 `./gradlew clean build` 통과. 세 서비스 실기동 end-to-end 시나리오(입찰 → 마감 → 낙찰 → 결제 성공/실패/유찰, 오류 코드) 확인. Testcontainers 통합 테스트는 없음(built, 단위 테스트만).
- 인프라: `.gitattributes`로 `*.sh` LF 고정(Windows 체크아웃에서 MySQL init 스크립트 실행 실패 수정). Gradle 빌드 캐시·병렬 빌드 활성화(`gradle.properties`).
- 문서: D3 일일 정리본(`docs/notes/D3-입찰-마감-낙찰-결제.md`).

## 완료 (D4)

- discovery-service(Eureka 서버, 8761) 모듈 추가. gateway·auction·bid·payment가 Eureka에 등록, Feign은 서비스 이름으로 호출(`clients.*.url`은 선택적 오버라이드).
- gateway: `/api/**` 라우팅(`lb://`), `/internal/**` 차단(404), JWT(HS256) 검증 → `X-User-Id` 주입·클라이언트 값 제거, `local` 프로필 전용 `POST /auth/token`, 라우트별 Circuit Breaker + 503 fallback, Gateway 오류의 표준 본문.
- bid-service: 경매 조회 Feign에 Circuit Breaker + FallbackFactory(업무 404·502는 그대로, 그 외 503 빠른 거절), 업무 4xx는 브레이커 실패에서 제외.
- auction-service: 낙찰 확정·결제 Feign에 Circuit Breaker(4xx 제외), 스케줄러가 브레이커 OPEN 시 그 주기의 남은 정산 중단.
- Actuator(gateway·auction·bid): `health`, `circuitbreakers`, `circuitbreakerevents`.
- Spring Boot 3.3.5 → 3.3.13 (Spring Cloud 2023.0.4 Gateway와의 NoSuchMethodError 해소).
- 검증 상태: 단위·컨텍스트 테스트 196개(auction 71, bid 61, payment 37, gateway 27) 통과, Docker·DB·Eureka 없이 `./gradlew clean build` 통과. 실기동(Eureka + 세 서비스 + Gateway, Gateway는 이 PC의 8080 충돌로 8090에서 실행) 확인: 토큰 발급 → 입찰 → 마감 → COMPLETED, 401(무토큰·조작 토큰), 위조 `X-User-Id` 무시, `/internal/**` 404, 없는 경매 입찰 8회에도 브레이커 CLOSED, bid-service 종료 시 Gateway 503·브레이커 OPEN(응답 약 2초 → 14ms)·auction-service 브레이커 OPEN·경매 `CLOSED + 낙찰자 없음` 유지, 재기동 후 밀린 경매 전부 COMPLETED. 확인하지 못한 것: payment-service 종료 시나리오, 만료 토큰의 실기동 확인(단위 테스트로만 검증).
- 문서: D4 일일 정리본(`docs/notes/D4-gateway-eureka-circuit-breaker.md`).

### D3의 임시 구조 (이후 일차에서 교체)

- 낙찰 → 결제가 auction-service의 동기 Feign 호출. D9에서 AuctionWon Kafka 이벤트로 대체.
- 정산 진행 단계를 `status + winner_id` 조합으로 표현(`CLOSED + NULL` = 정산 대기, `CLOSED + 있음` = 결제 실패). D9에서 재설계.
- 결제 실패 경매는 CLOSED에서 멈춘다. 차순위 승계·FAILED 전이는 D10.
- 입찰 동시성 제어 없음(검사~저장 사이 경합 가능). D6 낙관적 락, D7 분산 락.

## 완료 (D5)

- gRPC 전환 (auction-service → bid-service 낙찰 확정 1구간).
  - common 모듈: protobuf/gRPC 빌드 설정(`com.google.protobuf` 플러그인, `io.grpc` 1.63.0), `bid_winner.proto` 정의(`BidWinnerService.ConfirmWinner` RPC). 생성된 스텁 클래스: `BidWinnerServiceGrpc`, `ConfirmWinnerRequest/Response`, `WinningBid`.
  - bid-service: `BidWinnerGrpcService`(`@GrpcService`) — `BidService.confirmWinner()` 로직을 gRPC로 노출. `net.devh:grpc-server-spring-boot-starter:3.1.0.RELEASE`. gRPC 포트 9082. REST 엔드포인트(`InternalBidController`)는 디버깅·curl 테스트용으로 유지.
  - auction-service: `BidGrpcClient` — gRPC blocking stub + Resilience4j `CircuitBreaker.decorateSupplier()` 수동 연동. CB 인스턴스 이름 `bid-service` 재사용(기존 yml 설정 그대로 적용). `net.devh:grpc-client-spring-boot-starter:3.1.0.RELEASE`. 서비스 디스커버리 `discovery:///bid-service`. 데드라인은 기존 `read-timeout-ms`(5초) 재사용.
  - `AuctionSettlementScheduler`: `BidClient`(Feign) → `BidGrpcClient` 교체. `extractStatusInfo()`가 `StatusRuntimeException`과 `FeignException` 양쪽 상태를 로깅.
  - Feign `BidClient`·`BidClientConfig`는 제거하지 않음(payment-service Feign과 공유하는 `FeignConfig`·`@EnableFeignClients` 유지). 스케줄러가 더 이상 참조하지 않을 뿐.
  - root `build.gradle`: `implementation project(':common')`을 실제 사용하는 3개 서비스에만 선언(gateway·discovery-service는 common의 gRPC 전이 의존성을 받지 않음).
  - common의 gRPC 의존성을 `implementation`으로 두어 gateway·payment-service에 전이되지 않게 함. auction-service·bid-service는 `io.grpc:grpc-protobuf/stub`을 직접 선언.
- gRPC 버전 이슈: `grpc-spring-boot-starter:3.1.0.RELEASE`가 내부적으로 `grpc-core:1.63.0`을 사용하므로 명시 의존성도 1.63.0으로 통일(`ClassNotFoundException: io.grpc.InternalGlobalInterceptors` 해결).
- 검증 상태: 단위 테스트 200개(auction 71, bid 64 (+3), payment 37, gateway 28) 통과. `./gradlew clean build` 통과. 실기동 테스트(Eureka + 4서비스 + Gateway): Happy Path(토큰→상품→경매→입찰→만료→gRPC 낙찰 확정→COMPLETED) 확인. CB 장애 복구(bid-service kill → gRPC UNAVAILABLE 3회 → CB OPEN → auction CLOSED/winnerId=null 유지 → bid-service 재시작 → CB HALF_OPEN → 정산 성공 → COMPLETED) 확인.

## 완료 (D6)

- 낙관적 락(Optimistic Locking)으로 동시 입찰 직렬화.
  - Bid 엔티티에 `@Version` 추가. DB 스키마에 `version BIGINT NOT NULL DEFAULT 0` 컬럼 추가.
  - 동시에 같은 ACTIVE bid를 OUTBID로 전이하려 하면 `ObjectOptimisticLockingFailureException` 발생 → `GlobalExceptionHandler`가 409 `BID_CONFLICT`로 변환.
  - `BidConflictException` 예외 클래스 추가 (향후 서비스 레벨 재시도에 활용 가능).
  - k6 부하 테스트 스크립트 작성 (`infra/k6/concurrent-bid-test.js`).
- 검증 상태: 단위 테스트 201개(auction 71, bid 65 (+1), payment 37, gateway 28) 통과. `./gradlew clean build` 통과. 실기동 동시 입찰 테스트: 10개 동시 입찰 중 1개만 성공, 9개 BID_CONFLICT(409) 거절. DB에 ACTIVE bid 정확히 1개 유지. 재시도(순차) 정상 동작 확인.

### D6의 한계 (D7에서 해결)

- 낙관적 락은 충돌 시 실패+재시도 모델. 동시 요청이 많으면 재시도 비율이 높다.
- "조회 → 검증 → 쓰기" 전체를 직렬화하지 않아, 검증 단계의 읽기 경합은 여전히 존재.
- D7에서 auctionId 기준 분산 락(Redisson)으로 전체 직렬화 예정.

## 남은 범위

- **D7:** 분산 락(Redisson)으로 동시성 제어 강화, CI 파이프라인, Terraform 시작
- **D8~D13:** Kafka, Saga(낙찰→결제 이벤트화, 차순위 승계), Outbox, 멱등 컨슈머, Retry/DLQ
- **D14~D21:** CQRS, Event Sourcing(선택), K8s 배포, Terraform 심화
- **D22~D30:** 관측성, CD 완성, 부하 테스트, 문서화

밀릴 때 자르는 순서: Event Sourcing → Blue/Green → Alertmanager → gRPC → Loki.
