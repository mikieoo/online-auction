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
- 전체 빌드 `./gradlew clean build` 통과 확인.

## 남은 범위

- **D3:** REST API (입찰, 마감 스케줄러, 낙찰, 결제), OpenFeign 서비스 간 통신
- **D4:** Gateway + Eureka + Circuit Breaker
- **D5:** gRPC 전환 (auction↔bid 1구간)
- **D6~D7:** 동시성 제어 (낙관적 락 → 분산 락 비교), CI 파이프라인, Terraform 시작
- **D8~D13:** Kafka, Saga, Outbox, 멱등성, Retry/DLQ
- **D14~D21:** CQRS, Event Sourcing(선택), K8s 배포, Terraform 심화
- **D22~D30:** 관측성, CD 완성, 부하 테스트, 문서화

밀릴 때 자르는 순서: Event Sourcing → Blue/Green → Alertmanager → gRPC → Loki.
