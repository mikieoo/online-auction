# 프로젝트 컨텍스트

30일 MSA 포트폴리오 프로젝트. 도메인은 온라인 경매 시스템 (auction / bid / payment 서비스 + gateway).
전체 계획과 D1~D30 체크리스트는 `docs/PLAN.md`에 있다. 작업 시작 시 항상 먼저 읽을 것.

## 진행 방식

- 사용자가 "D12 시작" 처럼 말하면 `docs/PLAN.md`에서 해당 일차를 찾아 진행한다.
- 순서: 개념 설명 (왜 필요한지, 대안, 면접 질문) → 설계 뼈대만 제시 → 사용자가 직접 구현 → 코드 리뷰.
- 사용자가 요청하기 전에는 완성 코드를 통째로 작성하지 않는다. "빨리 가자"고 하면 그때 바로 구현한다.
- 리뷰 시 동시성 버그, 트랜잭션 경계, 놓친 실패 시나리오를 우선 확인한다.
- 일차가 끝나면 `docs/PLAN.md`의 체크박스를 체크하고 블로그 글 뼈대를 정리한다.

## 깊게 파는 항목 (나머지는 동작 수준까지만)

동시성 제어, Saga/보상 트랜잭션, Outbox, 멱등 컨슈머, Kafka 컨슈머 동작, Terraform, K8s

## 밀릴 때 자르는 순서

Event Sourcing → Blue/Green → Alertmanager → gRPC → Loki

## 기술 스택

Java / Spring Boot 3, Gradle 멀티 모듈, MySQL (서비스별 스키마), Redis (Redisson), Kafka,
Spring Cloud Gateway, Eureka (로컬만), OpenFeign → gRPC, Resilience4j,
Docker Compose, GKE Autopilot, Terraform, Helm, GitHub Actions,
관측성: Prometheus + Grafana (메트릭), Jaeger/Zipkin + OpenTelemetry (트레이싱), Loki + Promtail (로깅), Alertmanager (알람). 부하 테스트: k6.
Elasticsearch, EFK는 사용하지 않는다.

## 사용자 배경

Kafka, GitHub Actions CI 경험 있음. Terraform, K8s는 처음.
답변은 한국어로, 솔직하고 직접적으로.

## 기타

- 작업 종료 시 GKE 워크로드를 0으로 내리라고 상기시킬 것.
