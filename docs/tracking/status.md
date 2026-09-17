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
- 문서: D3 일일 정리본(`docs/blog/D3-입찰-마감-낙찰-결제.md`).

## 완료 (D4)

- discovery-service(Eureka 서버, 8761) 모듈 추가. gateway·auction·bid·payment가 Eureka에 등록, Feign은 서비스 이름으로 호출(`clients.*.url`은 선택적 오버라이드).
- gateway: `/api/**` 라우팅(`lb://`), `/internal/**` 차단(404), JWT(HS256) 검증 → `X-User-Id` 주입·클라이언트 값 제거, `local` 프로필 전용 `POST /auth/token`, 라우트별 Circuit Breaker + 503 fallback, Gateway 오류의 표준 본문.
- bid-service: 경매 조회 Feign에 Circuit Breaker + FallbackFactory(업무 404·502는 그대로, 그 외 503 빠른 거절), 업무 4xx는 브레이커 실패에서 제외.
- auction-service: 낙찰 확정·결제 Feign에 Circuit Breaker(4xx 제외), 스케줄러가 브레이커 OPEN 시 그 주기의 남은 정산 중단.
- Actuator(gateway·auction·bid): `health`, `circuitbreakers`, `circuitbreakerevents`.
- Spring Boot 3.3.5 → 3.3.13 (Spring Cloud 2023.0.4 Gateway와의 NoSuchMethodError 해소).
- 검증 상태: 단위·컨텍스트 테스트 196개(auction 71, bid 61, payment 37, gateway 27) 통과, Docker·DB·Eureka 없이 `./gradlew clean build` 통과. 실기동(Eureka + 세 서비스 + Gateway, Gateway는 이 PC의 8080 충돌로 8090에서 실행) 확인: 토큰 발급 → 입찰 → 마감 → COMPLETED, 401(무토큰·조작 토큰), 위조 `X-User-Id` 무시, `/internal/**` 404, 없는 경매 입찰 8회에도 브레이커 CLOSED, bid-service 종료 시 Gateway 503·브레이커 OPEN(응답 약 2초 → 14ms)·auction-service 브레이커 OPEN·경매 `CLOSED + 낙찰자 없음` 유지, 재기동 후 밀린 경매 전부 COMPLETED. 확인하지 못한 것: payment-service 종료 시나리오, 만료 토큰의 실기동 확인(단위 테스트로만 검증).
- 문서: D4 일일 정리본(`docs/blog/D4-gateway-eureka-circuit-breaker.md`).

### D3의 임시 구조 (이후 일차에서 교체)

- 낙찰 → 결제가 auction-service의 동기 Feign 호출. D9에서 AuctionWon Kafka 이벤트로 대체.
- 정산 진행 단계를 `status + winner_id` 조합으로 표현(`CLOSED + NULL` = 정산 대기, `CLOSED + 있음` = 결제 실패). D9에서 재설계.
- 결제 실패 경매는 CLOSED에서 멈춘다. 차순위 승계·FAILED 전이는 D10.
- 입찰 동시성 제어 없음(검사~저장 사이 경합 가능). D6 낙관적 락, D7 분산 락.

## 남은 범위

- **D5:** gRPC 전환 (auction↔bid 1구간)
- **D6~D7:** 동시성 제어 (낙관적 락 → 분산 락 비교), CI 파이프라인, Terraform 시작
- **D8~D13:** Kafka, Saga(낙찰→결제 이벤트화, 차순위 승계), Outbox, 멱등 컨슈머, Retry/DLQ
- **D14~D21:** CQRS, Event Sourcing(선택), K8s 배포, Terraform 심화
- **D22~D30:** 관측성, CD 완성, 부하 테스트, 문서화

밀릴 때 자르는 순서: Event Sourcing → Blue/Green → Alertmanager → gRPC → Loki.
