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

## 완료 (D7)

- 분산 락(Redisson)으로 동시 입찰 직렬화.
  - `BidLockFacade`: auctionId 기준 Redisson 분산 락으로 `BidService.placeBid()` 호출을 직렬화. 락 획득 대기 3초, 임대 5초. 락이 트랜잭션 바깥에 있어 커밋까지 보호.
  - `BidController`가 `BidService.placeBid()` 대신 `BidLockFacade.placeBidWithLock()`을 호출하도록 변경.
  - `@Version` 낙관적 락은 Redis 장애 시 안전망으로 유지.
  - `redisson-spring-boot-starter:3.30.0` 추가, `spring.data.redis` 설정.
- 검증 상태: 단위 테스트 205개(auction 71, bid 69 (+4), payment 37, gateway 28) 통과. `./gradlew clean build` 통과. 실기동 동시 입찰 비교:
  - D6(낙관적 락): 10개 동시 → 1 성공, 9 `BID_CONFLICT`(409, 커밋 시점 version 충돌).
  - D7(분산 락): 10개 동시 → 1 성공, 9 `BID_AMOUNT_TOO_LOW`(400, 순서대로 최신 가격을 읽고 정상 거절).
  - 분산 락 덕에 읽기 경합이 사라져, 충돌(409) 대신 정상 업무 거절(400)이 됨.

## 완료 (D8)

- Kafka 이벤트 프로듀서·컨슈머 구축 (auction-service → bid-service).
  - auction-service: `AuctionEventProducer` — `KafkaTemplate`으로 `auction-events` 토픽에 이벤트 발행. 파티션 키: `auctionId`(String). type header 활성화로 컨슈머 자동 타입 감지.
  - `AuctionController.startAuction()`: 트랜잭션 커밋 후(컨트롤러 레벨) `AuctionStartedEvent` 발행. phantom event 방지.
  - `AuctionSettlementScheduler.closeEndedAuctions()`: `closeAuction()` 트랜잭션 커밋 후 `AuctionClosedEvent` 발행.
  - bid-service: `AuctionEventConsumer` — `@KafkaListener` + `@KafkaHandler`로 `AuctionStartedEvent`/`AuctionClosedEvent` 타입별 핸들링. D8에서는 수신 로그만 남김.
  - bid-service: Kafka 컨슈머 설정 (`group-id: bid-service-group`, `JsonDeserializer`, `trusted.packages`).
  - 이벤트 DTO: D1에서 정의한 `AuctionStartedEvent`/`AuctionClosedEvent`(common 모듈) 그대로 사용.
- 검증 상태: 단위 테스트 213개(auction 76 (+5), bid 72 (+3), payment 37, gateway 28) 통과. `./gradlew clean build` 통과.

### D8의 한계 (이후 일차에서 해결)

- 이벤트 발행이 DB 트랜잭션과 원자적이지 않다: 트랜잭션 커밋 후 Kafka 발행 전에 애플리케이션이 죽으면 이벤트 유실. D11 Outbox 패턴으로 해결.
- bid-service 컨슈머가 로그만 남긴다. 경매 상태 로컬 캐시(Feign 조회 제거)는 향후 과제.
- `auction-events` 토픽의 나머지 이벤트(AuctionWonEvent, WinnerReassignedEvent)는 D9~D10에서 추가.

## 완료 (D9)

- Saga(Choreography): 동기 Feign 결제 호출을 Kafka 이벤트 기반 비동기 흐름으로 전환.
  - auction-service: 스케줄러에서 PaymentClient 의존성 완전 제거. 낙찰자 확정(`assignWinnerForPayment`) 후 `AuctionWonEvent` 발행. `PaymentEventConsumer`가 `payment-events` 토픽에서 `PaymentCompletedEvent` 수신 → `completeAuction()`(멱등).
  - payment-service: `AuctionWonEventConsumer`가 `auction-events` 토픽에서 `AuctionWonEvent` 수신 → `PaymentService.process()`(멱등키) → `PaymentEventProducer`가 결제 결과 이벤트 발행.
  - `AuctionSettlementService`: `completeWithWinner()`/`recordPaymentFailed()` 제거 → `assignWinnerForPayment()`(CLOSED 유지+winnerId 기록)/`completeAuction()`(CLOSED→COMPLETED, 멱등) 신설.
  - 상태 인코딩: CLOSED+winnerId=NULL(정산 대기), CLOSED+winnerId(결제 대기), COMPLETED(성공), FAILED+NULL(유찰).
  - idempotencyKey: `{auctionId}-{winnerId}-{reassignmentCount}` — 재지정마다 별도 결제.
- 검증 상태: 단위 테스트 220개(auction 76, bid 72, payment 44 (+7), gateway 28) 통과. `./gradlew clean build` 통과.

## 완료 (D10)

- 차순위 승계: 결제 실패 시 다음 최고가 입찰자에게 자동 재지정.
  - bid-service: `DISQUALIFIED` 상태 추가, `reassignWinner()` — WINNER→DISQUALIFIED + 최고가 OUTBID→WINNER. `ReassignWinner` gRPC RPC 추가. `markWinner()` OUTBID 허용(승격).
  - auction-service: `PaymentEventConsumer.handlePaymentFailed()` — bid-service gRPC 호출 → 차순위 있으면 `reassignWinner()`+`WinnerReassignedEvent` 발행, 없으면 `failAfterAllBiddersExhausted()`→FAILED.
  - payment-service: `AuctionWonEventConsumer`에 `handleWinnerReassigned()` 추가 — WinnerReassignedEvent 수신 시 결제 처리.
  - 연쇄 승계: 각 실패마다 DISQUALIFIED로 영구 제외, OUTBID가 고갈되면 FAILED.
- 검증 상태: 단위 테스트 235개(auction 81 (+5), bid 79 (+7), payment 47 (+3), gateway 28) 통과. `./gradlew clean build` 통과.

## 완료 (D11)

- Outbox 패턴(Polling Publisher): DB 트랜잭션과 Kafka 이벤트 발행의 원자성 보장.
  - auction-service·payment-service 양쪽에 outbox 테이블 + ShedLock 테이블 추가.
  - `OutboxEvent` 엔티티, `OutboxRepository`, `OutboxEventRecorder`(트랜잭션 안에서 호출), `OutboxPublisher`(@Scheduled + @SchedulerLock + @Transactional 폴링 발행).
  - 6개 발행 지점을 모두 Kafka 직접 발행 → outbox INSERT로 전환:
    - auction: `startAuction(AuctionStartedEvent)`, `closeAuction(AuctionClosedEvent)`, `assignWinnerForPayment(AuctionWonEvent)`, `reassignWinner(WinnerReassignedEvent)`.
    - payment: `createAndSettle(PaymentCompletedEvent/PaymentFailedEvent)`, `resettle(동일)`.
  - `AuctionEventProducer`, `PaymentEventProducer` 삭제. 컨트롤러·스케줄러·컨슈머에서 프로듀서 의존성 제거.
  - OutboxPublisher: JSON 직렬화 → outbox 저장 → 폴러가 역직렬화 후 KafkaTemplate으로 전송(type header 유지) → at-least-once 보장.
- 검증 상태: 단위 테스트 223개(auction 74, bid 79, payment 42, gateway 28) 통과. `./gradlew clean build` 통과.

## 완료 (D12)

- 멱등 컨슈머(Idempotent Consumer): eventId 기반 중복 처리 차단.
  - auction_db에 `processed_event` 테이블 추가 (event_id PK).
  - `ProcessedEvent` 엔티티, `ProcessedEventRepository`, `IdempotentHandler` 신규.
  - `IdempotentHandler.processOnce()`: processed_event INSERT + 비즈니스 로직을 한 트랜잭션에서 실행. UNIQUE 위반(동시 처리) 시 false 반환.
  - `IdempotentHandler.isAlreadyProcessed()`: gRPC 등 외부 호출 전 사전 체크.
  - `PaymentEventConsumer` 수정:
    - `handlePaymentCompleted`: `processOnce()`로 감싸 중복 건너뜀.
    - `handlePaymentFailed`: 사전 체크(`isAlreadyProcessed`) → gRPC → `processOnce()`로 DB 쓰기. 가장 위험했던 차순위 승계 중복 호출 방어.
  - payment-service는 변경 없음 — idempotencyKey UNIQUE 제약으로 이미 멱등.
- 검증 상태: 단위 테스트 231개(auction 82 (+8), bid 79, payment 42, gateway 28) 통과. `./gradlew clean build` 통과.

## 완료 (D13)

- Retry/DLQ: Spring Kafka `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`로 재시도/DLQ 체계 구축.
  - auction-service·payment-service 양쪽에 `KafkaConsumerConfig` 추가.
  - `DefaultErrorHandler`: `FixedBackOff(1000L, 2)` — 1초 간격, 최대 2회 재시도(총 3회 시도).
  - `DeadLetterPublishingRecoverer`: 재시도 소진 후 `<원본토픽>.DLT` 토픽으로 실패 메시지 발행.
  - `DeserializationException`은 재시도 없이 즉시 DLQ 전송.
  - 컨슈머 try-catch 제거: `PaymentEventConsumer`, `AuctionWonEventConsumer`에서 예외를 삼키지 않고 전파 → `DefaultErrorHandler`가 재시도/DLQ 처리.
  - `DefaultErrorHandler` 선택 이유: auctionId 파티션 키 기반 순서 보장이 필요하므로 같은 파티션 내에서 재시도하는 방식 선택 (`@RetryableTopic`은 별도 토픽으로 보내 순서 깨짐).
- 검증 상태: 단위 테스트 231개(auction 82, bid 79, payment 42, gateway 28) 통과. `./gradlew clean build` 통과.

## 완료 (D14)

- Docker 컨테이너화: 5개 서비스(auction, bid, payment, gateway, discovery) Multi-stage Dockerfile 작성.
  - 빌드: `gradle:8.10-jdk17` → 런타임: `eclipse-temurin:17-jre` (arm64+amd64 지원).
  - `.dockerignore`로 빌드 컨텍스트 최소화.
  - 5개 이미지 빌드 검증 완료.
- Docker Compose 확장: 기존 인프라(MySQL, Redis, Kafka, Zipkin) + Java 서비스 5개 통합.
  - Kafka 듀얼 리스너: INTERNAL(29092, Docker 내부) + EXTERNAL(9092, 호스트).
  - `depends_on` + `healthcheck`로 기동 순서 보장.
  - `application-docker.yml` 프로필로 호스트명 오버라이드 (localhost → 컨테이너명).
- GitHub Actions CI: `.github/workflows/ci.yml` — `build-and-test` + `docker-build` (5개 서비스 matrix).
- 검증 상태: 단위 테스트 231개 통과. 5개 서비스 Docker 이미지 빌드 성공.

## 완료 (D15)

- Helm Chart: 5개 서비스를 하나의 차트로 관리하는 `infra/helm/auction-platform/` 작성.
  - `range`로 서비스 순회 → Deployment, Service, ConfigMap 자동 생성 (16개 K8s 오브젝트).
  - ConfigMap: 서비스별 DB URL, Kafka, Redis, Eureka 환경 변수. Spring Boot 환경 변수 오버라이드 방식.
  - Secret: mysql-password, jwt-secret (base64 인코딩).
  - Deployment: readiness/liveness probe (`/actuator/health`), resource requests/limits.
  - Service: gateway만 LoadBalancer, 나머지 ClusterIP. bid-service는 HTTP+gRPC 두 포트.
- 검증 상태: `helm lint` 통과, `helm template` 16개 오브젝트 정상 렌더링.

## 완료 (D16)

- Terraform: GKE Autopilot + Artifact Registry + VPC 인프라 코드 작성.
  - `infra/terraform/`: main.tf, variables.tf, vpc.tf, gke.tf, artifact-registry.tf, outputs.tf.
  - VPC: 수동 서브넷 + Pod/Service 보조 IP 대역 (VPC-native 클러스터).
  - GKE Autopilot: Pod requests 기준 과금, dev에서는 deletion_protection 끔.
  - Artifact Registry: Docker 이미지 저장소 + 30일 미태그 이미지 자동 정리.
  - Outputs: 클러스터 이름, 엔드포인트, 레지스트리 URL, kubectl 인증 명령어.
  - State: 로컬 파일 (.gitignore로 제외).
- 검증 상태: `terraform init` + `terraform validate` 통과. 실제 프로비저닝은 GCP 프로젝트 설정 후 진행.

## 완료 (D17)

- 분산 트레이싱: Micrometer Tracing + OpenTelemetry + Zipkin.
  - root build.gradle에 tracing 의존성 추가 (micrometer-tracing-bridge-otel, opentelemetry-exporter-zipkin).
  - 5개 서비스 application.yml: sampling probability 1.0, Zipkin 엔드포인트.
  - Kafka observation-enabled: auction/bid/payment 3개 서비스 — 비동기 구간도 trace 전파.
  - 로그 패턴에 traceId/spanId 포함 — 로그 검색으로 요청 흐름 추적 가능.
  - application-docker.yml: Zipkin 엔드포인트 오버라이드 (5개 서비스).
  - 별도 코드 없이 의존성 + 설정만으로 자동 계측 (HTTP, Feign, gRPC, Kafka, JDBC).
- 검증 상태: 단위 테스트 231개 통과. `./gradlew clean build` 통과.

## 완료 (D18)

- 메트릭 모니터링: Prometheus + Grafana.
  - root build.gradle에 `micrometer-registry-prometheus` 의존성 추가.
  - 5개 서비스 application.yml: `/actuator/prometheus` 엔드포인트 노출 + application 태그.
  - Prometheus 설정: `infra/prometheus/prometheus.yml` — 5개 서비스 15초 간격 스크래핑.
  - Grafana 프로비저닝: 데이터소스(Prometheus) + 대시보드 자동 로드.
  - 대시보드 패널: Request Rate, Error Rate, Response Time p95/p99, JVM Heap, JVM Threads, HikariCP, Circuit Breaker State.
  - Docker Compose: Prometheus(9091), Grafana(3000) 컨테이너 추가.
  - 별도 코드 없이 의존성 + 설정만으로 메트릭 수집·시각화.
- 검증 상태: 단위 테스트 231개 통과. `./gradlew clean build` 통과.

## 완료 (D19)

- CD 파이프라인: GitHub Actions → Artifact Registry → GKE Helm 배포.
  - CI/CD 분리: `ci.yml`(빌드+테스트, PR은 docker-build 검증), `cd.yml`(main push 시 배포).
  - `docker-push` job: Workload Identity Federation으로 GCP 인증, 5개 서비스 Artifact Registry push (matrix 병렬).
  - `deploy` job: `helm upgrade --install`로 GKE 배포, `--wait --timeout 5m`.
  - 이미지 태그: git SHA (불변, 추적·롤백 가능).
  - Helm values.yaml: `global.image.registry` 추가. CD에서 `--set`으로 주입, 로컬은 빈 문자열.
  - Deployment 템플릿: registry 유무에 따라 이미지 경로 조건 렌더링.
  - GCP 프로젝트 없이 코드·구조 검증까지만 진행.
- 검증 상태: `helm lint` 통과, `helm template` 이미지 경로 정상 렌더링. 단위 테스트 231개 통과.

## 완료 (D20)

- 부하 테스트(k6): 시나리오 3개(`infra/k6/hot-auction.js`, `bid-throughput.js`, `settlement-drain.js`) + 공유 헬퍼(`lib/api.js`).
  - `infra/k6/compose.loadtest.yml` 오버레이: 프로젝트 이름 분리(`auction-loadtest`), gateway `local` 프로필(토큰 발급기), Prometheus remote-write 수신. k6 0.54.0을 같은 Docker 네트워크에서 실행.
  - 입찰 결과를 업무 단위로 분류(accepted / too_low / conflict / unavailable / error)해 시스템 실패만 threshold로 막고, 끝난 뒤 불변 조건(ACTIVE 1개·최고가 일치, 정산 최종 상태)을 검증.
- 발견·수정: 입찰 트랜잭션 안의 auction-service 조회(Feign)가 부하 시 bid-service 커넥션 풀(10)을 고갈시켰다(대기 최대 29, 보유 58ms 중 조회 48ms). `BidService.prepareBid`(조회·검사, 락·트랜잭션 밖) → 분산 락 → `BidService.placeBid`(마감·금액 검사 + 저장, 짧은 트랜잭션)로 재구성. 락 안의 원격 호출(최악 7초)이 락 임대 시간(5초)을 넘는 문제도 함께 사라졌다.
  - 경매 50개·초당 600건 점증: p95 692ms → 270ms, 409 156 → 0, 커넥션 대기 29 → 0.
  - 경매 1개·초당 150건: p95 2.17s → 151ms, 409 15 → 0. 경매당 처리 상한 약 100 → 약 250건/초.
  - 경매 200개 동시 마감: 23.3초에 전부 정산, 최종 상태 200/200 일치. 소요 시간은 처리 속도가 아니라 배치 상한(50) × 주기(5초)가 정한다(틱당 0.5~0.8초 사용).
- 문서: `docs/notes/D20-부하-테스트.md`, operations.md 부하 테스트 절, standards.md(원격 호출을 트랜잭션·분산 락 안에 두지 않는다), engineering-notes 4항목, findings 3건 추가.
- 검증 상태: 단위 테스트 233개(auction 82, bid 81 (+2), payment 42, gateway 28) 통과. `./gradlew clean build` 통과. 전체 스택 컨테이너 실기동에서 세 시나리오 수정 전후 측정.

## 완료 (D21)

- ACTIVE 유일성 DB 제약: MySQL에 조건부 UNIQUE가 없어 생성 컬럼 + UNIQUE로 흉내.
  - auction: `active_product_id = IF(status='ACTIVE', product_id, NULL)` + `uk_auction_active_product`. bid: `active_auction_id` + `uk_bid_active_auction`. 엔티티에는 매핑하지 않음.
  - `AuctionService.startAuction`: `saveAndFlush`로 위반을 받아 409 `ACTIVE_AUCTION_EXISTS`로 변환(다른 무결성 위반은 그대로), WARN 로그.
  - `BidService.placeBid`: 이전 ACTIVE의 OUTBID UPDATE를 `saveAndFlush`로 먼저 내보낸 뒤 새 입찰 INSERT(IDENTITY라 INSERT가 즉시 나가 순서를 지키지 않으면 모든 정상 입찰이 제약에 걸린다). 위반은 409 `BID_CONFLICT`.
  - 사전 검사(`ensureNoActiveAuction`)·분산 락은 빠른 거절·순서 처리용으로 유지, 제약은 최종 방어.
- 경합 재현 스크립트 `infra/k6/concurrent-start.js`: 같은 상품 경매 두 개 동시 시작 100회 → 정확히 하나만 시작 100/100, DB 제약이 막은 횟수 100(두 요청이 매번 사전 검사를 함께 통과).
- 세 서비스의 `schema.sql` 원본을 init 사본과 다시 맞춤(auction `processed_event`, bid `version`, payment `outbox`·`shedlock` 누락).
- findings에서 해결된 2건 제거(ACTIVE Bid 2개, 같은 상품 동시 시작).
- 검증 상태: 단위 테스트 238개(auction 84 (+2), bid 84 (+3), payment 42, gateway 28) 통과. `./gradlew clean build` 통과. 실기동: SQL 수준 제약 확인, 경합 재현 100회, D20 시나리오 회귀(인기 경매 150/s 409 0건·불변 조건 통과, 동시 마감 200개 200/200).
- 문서: `docs/notes/D21-ACTIVE-유일성-DB-제약.md`, engineering-notes(조건부 UNIQUE, flush 순서), standards(DB 절), business-rules, operations.

## 문서 정비 (D21 이후)

- 루트 `README.md` 신설: 구성, 기술 스택, 문제-해결 요약표(D4~D21 기록 링크), D20·D21 측정 결과, 실행 방법, 문서 색인, 현재 한계.
- `docs/architecture.md`를 D21 기준으로 재작성: D3 동기 정산 설명을 걷어내고 gRPC·Kafka·Outbox·멱등 소비·DLT, 동시성 제어 표, D20 이후 입찰 흐름, Saga 흐름, 배포 구성(Compose·CI/CD·Helm·Terraform), 관측성 추가.

## 남은 범위
- **D22~:** Event Sourcing(선택)
- **D22~D30:** 추가 관측성, 문서화

밀릴 때 자르는 순서: Event Sourcing → Blue/Green → Alertmanager → gRPC → Loki.
