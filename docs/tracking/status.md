# 진행 상황

## 완료 (D1)

- Gradle 멀티 모듈 프로젝트: root + auction-service, bid-service, payment-service, gateway, common. `./gradlew clean build` 통과 확인.
- DB 스키마: auction_db(product, auction, outbox), bid_db(bid), payment_db(payment). Docker Compose 기동 후 3개 DB 테이블 생성 확인.
- Docker Compose: MySQL 8.0 단일 인스턴스 + 3개 DB. `infra/mysql/init/` 초기화 스크립트로 자동 생성.
- 공유 이벤트 DTO: 7개 이벤트 클래스(common 모듈). 빌드 통과 확인.

## 남은 범위

- **D2~D3:** REST API (상품 등록, 경매 생성/시작/마감, 입찰, 결제)
- **D4:** Gateway + Eureka + Circuit Breaker
- **D5:** gRPC 전환 (auction↔bid 1구간)
- **D6~D7:** 동시성 제어 (낙관적 락 → 분산 락 비교), CI 파이프라인, Terraform 시작
- **D8~D13:** Kafka, Saga, Outbox, 멱등성, Retry/DLQ
- **D14~D21:** CQRS, Event Sourcing(선택), K8s 배포, Terraform 심화
- **D22~D30:** 관측성, CD 완성, 부하 테스트, 문서화

밀릴 때 자르는 순서: Event Sourcing → Blue/Green → Alertmanager → gRPC → Loki.
