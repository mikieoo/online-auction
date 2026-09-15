# MSA 사이드 프로젝트 기획 정리

> 확정일: 2026-09-14 / 기간: 30일 / 도메인: 온라인 경매 시스템

## 프로젝트 목표

- MSA 패턴 학습 + 취업 포트폴리오
- 다양한 기술 스택 경험 (전부 "동작하는 최소 구성"까지 구현)
- 깊이는 7개 항목에 집중 (아래 "깊게 파는 항목" 참고)

## 전제 조건

- 하루 8시간 이상 투입
- Kafka, GitHub Actions CI는 경험 있음 → 해당 일정 축소
- Terraform, K8s는 처음 → 1주차부터 병행 시작
- 코드는 직접 작성하고 Claude는 개념 설명 → 설계 뼈대 → 코드 리뷰 순서로 진행

---

## 기술 스택

| 구분 | 기술 |
|------|------|
| 언어/프레임워크 | Java / Spring Boot 3 |
| 서비스 통신 (동기) | REST (OpenFeign) → gRPC 전환 (경매↔입찰 1구간) |
| 서비스 통신 (비동기) | Apache Kafka |
| API Gateway | Spring Cloud Gateway |
| Service Discovery | Eureka (로컬) → K8s Service DNS (GKE) |
| 캐싱/분산 락 | Redis (Redisson) |
| DB | MySQL (서비스별 독립 스키마) |
| 컨테이너 | Docker, Docker Compose |
| 오케스트레이션 | Kubernetes (GCP GKE Autopilot) |
| IaC | Terraform |
| CI/CD | GitHub Actions |
| 관측성 - 메트릭 | Prometheus + Grafana (kube-prometheus-stack), Actuator + Micrometer |
| 관측성 - 트레이싱 | Zipkin (로컬) / Jaeger + OpenTelemetry (GKE) |
| 관측성 - 로깅 | Loki + Promtail (Grafana 통합 조회) |
| 관측성 - 알람 | Alertmanager → Slack |
| 부하 테스트 | k6 |

- Elasticsearch, EFK는 사용하지 않음 (비용, 시간)

---

## 도메인: 온라인 경매 시스템

```
상품등록 → 경매시작 → 실시간 입찰 → 마감 → 낙찰 → 결제 → (실패 시 차순위 승계)
```

### 선정 이유

- 포트폴리오에 드물어 차별화됨, 도메인 설명 불필요
- 동시 입찰 = 동시성 제어가 도메인의 핵심 (면접 단골)
- 낙찰 → 결제 → 실패 시 차순위 승계 = Saga + 보상 트랜잭션이 자연스러움
- 입찰 이력 = Event Sourcing 교과서 사례, 경매 현황판 = CQRS 읽기 모델
- k6 동시 입찰 부하 테스트로 수치 어필 가능
- 경매 마감 스케줄러 중복 실행 방지 (ShedLock) 등 숨은 기술 챌린지

### 서비스 구성 (3개 + Gateway)

| 서비스 | 책임 |
|--------|------|
| auction-service | 상품, 경매 생성/시작/마감, 낙찰 결정 |
| bid-service | 입찰 접수, 동시성 제어, 입찰 이력, 현황 읽기 모델 |
| payment-service | 결제, 결제 실패 시 보상 이벤트 발행 |
| gateway | 라우팅, 인증(선택) |

### 핵심 도메인 이벤트

- AuctionStarted, BidPlaced, AuctionClosed, AuctionWon
- PaymentCompleted, PaymentFailed, WinnerReassigned

### MVP 기능 범위

상품 등록, 경매 시작/마감, 입찰, 낙찰, 결제 성공/실패. 알림은 제외.

### 검토했던 다른 후보

- 물류/배송 추적: Saga 자연스럽지만 포트폴리오에 흔함
- 간소화 MES: 차별화되지만 면접관에게 도메인 설명 부담

---

## 깊게 파는 항목 (7개)

나머지는 "붙여봤고 왜 쓰는지 설명 가능" 수준까지만.

1. 동시성 제어 (낙관적 락 vs Redis 분산 락, 수치 비교)
2. Saga + 보상 트랜잭션 (결제 실패 → 차순위 승계)
3. Transactional Outbox (DB 커밋과 이벤트 발행의 원자성)
4. 멱등 컨슈머 (중복 메시지 처리)
5. Kafka 컨슈머 동작 (리밸런싱, 오프셋 커밋, Retry/DLQ)
6. Terraform (GKE, Cloud SQL, 모듈화)
7. Kubernetes (Deployment, Service, Ingress, HPA, Helm)

---

## 30일 로드맵

### 1주차: 기반 세팅 + 동기 통신 (Phase 1, 2)

- [ ] **D1** 도메인 설계: 이벤트 스토밍, 바운디드 컨텍스트 3개, 서비스별 DB 스키마, Gradle 멀티 모듈 레포 생성
- [ ] **D2** Docker Compose (MySQL, Redis, Kafka, Zipkin, Kafka UI), 3개 서비스 뼈대, 상품 등록/경매 시작
- [ ] **D3** 입찰, 마감(ShedLock 스케줄러), 낙찰, 결제 핵심 기능, REST + OpenFeign 서비스 간 통신
- [ ] **D4** Spring Cloud Gateway + Eureka + Resilience4j Circuit Breaker (fallback 포함)
- [ ] **D5** gRPC 전환: 경매↔입찰 1구간, 입찰 스트리밍 (grpc-spring-boot-starter)
- [ ] **D6** 동시성 1: 낙관적 락 구현, k6 동시 입찰 부하 테스트, 실패율 기록
- [ ] **D7** 동시성 2: Redisson 분산 락, 비교표 작성 / CI (테스트→빌드→Artifact Registry 푸시) / Terraform: GCP 프로젝트 + VPC

### 2주차: 비동기 + 이벤트 드리븐 (Phase 3)

- [ ] **D8** Kafka 도입: 토픽 설계, 이벤트 발행/구독, 파티션 키 = auctionId
- [ ] **D9** Saga 1: Choreography, AuctionWon → 결제 → PaymentCompleted
- [ ] **D10** Saga 2: 보상 트랜잭션, PaymentFailed → WinnerReassigned, 실패 시나리오 테스트
- [ ] **D11** Outbox 1: outbox 테이블, 같은 트랜잭션에 저장, 폴러 구현
- [ ] **D12** Outbox 2: 폴러 중복 발행 처리, 브로커 다운 중 재발행 테스트 / Redis 캐싱 (경매 현황)
- [ ] **D13** 멱등 컨슈머 (idempotency key 테이블) + Retry/DLQ (DefaultErrorHandler, DeadLetterPublishingRecoverer)
- [ ] **D14** Terraform: Cloud SQL + GKE Autopilot 프로비저닝 / 블로그 1편 (동시성 비교)

### 3주차: 데이터 심화 + K8s (Phase 4, 5)

- [ ] **D15** CQRS: BidPlaced 컨슈밍 → Redis 읽기 모델, 조회 API 분리
- [ ] **D16** Event Sourcing: 입찰 애그리거트만 이벤트 스토어 방식 (append-only + 리플레이)
- [ ] **D17** 버퍼 (여유 시 MySQL 풀텍스트 상품 검색)
- [ ] **D18** K8s 매니페스트 (Deployment, Service, ConfigMap, Secret), GKE 1차 배포, Cloud SQL 연결
- [ ] **D19** Ingress, HPA, Helm Chart 패키징 (helm create 기반)
- [ ] **D20** Terraform 모듈화, dev/prod 변수 분리, remote state
- [ ] **D21** 버퍼 / 블로그 2편 (Saga + Outbox)

### 4주차: 관측성 + CI/CD 완성 (Phase 6, 7)

- [ ] **D22** kube-prometheus-stack, Actuator + Micrometer, Grafana 대시보드
- [ ] **D23** Jaeger Helm + OpenTelemetry 에이전트, 분산 트레이싱 확인
- [ ] **D24** Loki + Promtail Helm, Grafana에서 메트릭/로그 통합 조회
- [ ] **D25** Alertmanager → Slack 알람 (에러율, 파드 재시작)
- [ ] **D26** CD: 이미지 푸시 → Helm upgrade GKE 배포 / Terraform plan/apply 워크플로
- [ ] **D27** Blue/Green 배포 (Deployment 2개 + Service selector 전환)
- [ ] **D28** GKE 부하 테스트, HPA 동작 확인, 장애 시나리오 시연 (파드 kill, Circuit Breaker OPEN)
- [ ] **D29** README, 아키텍처 다이어그램, 블로그 3편 (K8s + 관측성)
- [ ] **D30** 버퍼

### 밀릴 때 자르는 순서

1. Event Sourcing (D16)
2. Blue/Green (D27)
3. Alertmanager (D25)
4. gRPC (D5)
5. Loki (D24) → GKE Cloud Logging으로 대체

동시성 비교, Saga, Outbox, 멱등성, K8s 배포는 마지막까지 유지.

---

## 일일 진행 방식

1. 그날 항목의 개념 설명 (왜 필요한지, 대안, 면접 질문)
2. 설계 방향 + 뼈대만 잡고 직접 구현
3. 코드 리뷰 (동시성 버그, 트랜잭션 경계, 놓친 실패 시나리오)
4. 블로그 글 뼈대 정리
5. 작업 종료 시 GKE 워크로드 0으로 내리기

---

## 핵심 MSA 패턴 요약

### Saga

- 여러 서비스에 걸친 트랜잭션을 보상 트랜잭션으로 관리
- Choreography: 이벤트 기반, 중앙 제어 없음 (서비스 3~4개 이하에 적합) → 이 프로젝트 선택
- Orchestration: 중앙 조율자가 제어 (복잡한 흐름에 적합)

### Transactional Outbox

- DB 저장과 이벤트 발행의 원자성 보장
- 같은 DB 트랜잭션 안에 Outbox 테이블에 이벤트 저장
- 별도 폴러가 Outbox → Kafka로 발행 (Debezium은 시간 남으면)

### Idempotency (멱등성)

- 같은 요청을 여러 번 보내도 결과가 동일하게 보장
- Idempotency Key를 통해 중복 요청 감지
- 결제, Kafka 컨슈머 등에서 필수

### Retry / DLQ

- Retry: 실패 시 Exponential Backoff로 재시도
- DLQ (Dead Letter Queue): 재시도 전부 실패한 메시지를 별도 큐에 보관
- 나중에 원인 파악 후 수동/자동 재처리

### Circuit Breaker

- 장애 전파 차단 패턴
- 상태: CLOSED (정상) → OPEN (차단) → HALF_OPEN (시험)
- Resilience4j로 구현, fallback 응답 반환

### Distributed Tracing (분산 트레이싱)

- 여러 서비스를 거치는 요청을 하나의 traceId로 추적
- 병목 지점, 에러 발생 위치 파악
- Zipkin / Jaeger로 시각화

### 패턴 간 관계

```
요청 → Circuit Breaker (장애 차단)
     → Saga (분산 트랜잭션)
       → Outbox (이벤트 발행 보장)
       → Kafka (이벤트 전달)
         → Retry (실패 시 재시도)
         → DLQ (재시도 실패 보관)
       → Idempotency (중복 처리 방지)
     → Distributed Tracing (전체 흐름 추적)
```

---

## Terraform 학습 순서

1. GCP 프로젝트 + VPC 생성 → 기본 문법 익히기 (D7)
2. Cloud SQL 인스턴스 + GKE Autopilot 프로비저닝 → 변수, state 관리, plan/apply (D14)
3. 모듈화 + 환경 분리 (dev/prod), remote state → 실무 패턴 (D20)
4. plan/apply 자동화 (D26)

## GCP 비용 참고

- GKE Autopilot 1개 클러스터 관리비 무료
- 신규 계정 $300 크레딧 (90일)
- Elasticsearch 제외로 비용 부담 큰 폭 감소
- 작업 종료 시 워크로드 0으로 내려두면 비용 절감

---

## 설계 원칙

- 서비스는 3개 + Gateway로 고정, 깊이는 7개 항목에 집중
- 각 Phase마다 블로그 글 작성 → 포트폴리오 자연 축적 (D14, D21, D29)
- 면접 질문은 "왜 그렇게 했나" → 대안 비교와 수치를 항상 기록
- 한 달은 "완성", 그 뒤가 "심화"
