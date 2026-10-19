# 온라인 경매 시스템 (MSA)

1인 개발 30일 MSA 학습·포트폴리오 프로젝트. 경매 등록 → 입찰 → 마감 → 낙찰 → 결제 흐름을 서비스 3개로 나누고, 그 사이에서 생기는 동시성·분산 트랜잭션·장애 전파 문제를 직접 겪고 푼 기록이다.

## 구성

```
클라이언트 ─(JWT)→ Gateway ─┬→ auction-service ─gRPC→ bid-service
                            ├→ bid-service ─REST→ auction-service
                            └→ payment-service
        auction-service ⇄ payment-service : Kafka (Saga, Outbox)
        서비스 탐색: Eureka(discovery-service)
```

| 서비스 | 책임 | DB |
|---|---|---|
| gateway | JWT 검증, `X-User-Id` 주입, 라우팅, Circuit Breaker | — |
| auction-service | 상품·경매, 마감·정산 스케줄러, Saga 진행 | auction_db |
| bid-service | 입찰 접수·검증, 최고가 관리, 낙찰·차순위 확정 | bid_db |
| payment-service | 결제 생성·시뮬레이션 | payment_db |
| discovery-service | Eureka 서버 | — |
| common | 이벤트 DTO, gRPC proto | — |

자세한 구성과 흐름: [`docs/architecture.md`](docs/architecture.md)

## 기술 스택

Java 17 · Spring Boot 3.3 · Spring Cloud 2023.0 (Gateway, OpenFeign, Eureka, Resilience4j) · gRPC · Kafka · MySQL 8 · Redis(Redisson) · ShedLock · Micrometer Tracing + Zipkin · Prometheus + Grafana · Docker Compose · Helm · Terraform(GKE Autopilot) · GitHub Actions · k6

## 무엇을 풀었나

| 문제 | 해결 | 기록 |
|---|---|---|
| 같은 경매에 동시 입찰하면 최고가가 꼬인다 | `@Version` 낙관적 락 → auctionId 기준 Redisson 분산 락 | [D6](docs/notes/D6-낙관적-락.md), [D7](docs/notes/D7-분산-락.md) |
| 서비스마다 DB가 달라 낙찰·결제를 한 트랜잭션으로 묶을 수 없다 | Choreography Saga, 결제 실패 시 차순위 승계로 보상 | [D9](docs/notes/D9-Saga-결제-이벤트.md), [D10](docs/notes/D10-차순위-승계.md) |
| DB 커밋 후 Kafka 발행 전에 죽으면 이벤트가 사라진다 | Transactional Outbox(폴링 발행) | [D11](docs/notes/D11-Outbox-패턴.md) |
| at-least-once라 같은 이벤트가 두 번 온다 | `processed_event` 테이블, 결제 멱등키 | [D12](docs/notes/D12-멱등-컨슈머.md) |
| 처리 실패 메시지가 파티션을 막는다 | 재시도 2회 후 DLT, 파티션 순서 유지 | [D13](docs/notes/D13-Retry-DLQ.md) |
| 한 서비스 장애가 호출한 쪽까지 번진다 | Gateway·서비스 간 2층 Circuit Breaker, 업무 4xx는 실패로 세지 않음 | [D4](docs/notes/D4-gateway-eureka-circuit-breaker.md) |
| 부하 시 입찰 응답이 느려지고 409가 난다 | 원격 조회를 트랜잭션·락 밖으로 이동 (아래 표) | [D20](docs/notes/D20-부하-테스트.md) |
| 같은 상품 경매 두 개를 동시에 시작하면 둘 다 ACTIVE가 된다 | 생성 컬럼 + UNIQUE로 DB가 "ACTIVE는 하나"를 강제 | [D21](docs/notes/D21-ACTIVE-유일성-DB-제약.md) |

### 부하 테스트로 찾은 병목 (D20)

입찰 트랜잭션 안에서 auction-service를 Feign으로 조회하고 있었다. 응답을 기다리는 동안 DB 커넥션을 붙잡아, 부하가 오르면 커넥션 풀(10개)이 먼저 고갈됐다. 조회를 락·트랜잭션 밖으로 빼고 락 안에는 DB 작업만 남겼다.

| 시나리오 (k6, 로컬 Docker) | 수정 전 | 수정 후 |
|---|---|---|
| 경매 50개, 초당 600건까지 점증: p95 | 692ms | 270ms |
| 같은 시나리오: 409 충돌 | 156건 | 0건 |
| 같은 시나리오: 커넥션 대기 최대 | 29 | 0 |
| 인기 경매 1개, 초당 150건: p95 | 2.17s | 151ms |

k6가 같은 PC에서 돌아 절대 수치보다 전후 비교로 읽어야 한다.

### 동시 시작 경합 (D21)

같은 상품의 WAITING 경매 두 개를 동시에 시작하는 요청을 100번 보냈다. 100번 모두 두 요청이 사전 검사를 함께 통과했고, DB 제약이 매번 하나를 409로 막았다. 사전 검사만으로는 막지 못한다는 것이 재현으로 확인됐다.

## 실행

필요: JDK 17+, Docker

```bash
docker compose up -d                 # MySQL·Redis·Kafka·Zipkin·Prometheus·Grafana
./gradlew clean build                # 빌드 + 단위 테스트 238개 (Docker 불필요)

./gradlew :discovery-service:bootRun
./gradlew :auction-service:bootRun
./gradlew :bid-service:bootRun
./gradlew :payment-service:bootRun
JWT_SECRET='32바이트-이상의-임의-문자열-0123456789' ./gradlew :gateway:bootRun --args='--spring.profiles.active=local'
```

API 호출 예시, 부하 테스트 실행, 문제 해결은 [`docs/operations.md`](docs/operations.md).

| 화면 | 주소 |
|---|---|
| Gateway | `localhost:8080` |
| Eureka | `localhost:8761` |
| Kafka UI | `localhost:9090` |
| Zipkin | `localhost:9411` |
| Grafana | `localhost:3000` |

## 문서

| 문서 | 내용 |
|---|---|
| [`docs/architecture.md`](docs/architecture.md) | 서비스 구성, 통신 방식, 데이터 소유권, 동시성 제어, 배포·관측성 |
| [`docs/business-rules.md`](docs/business-rules.md) | 입찰 규칙, 상태 전이 |
| [`docs/contracts.md`](docs/contracts.md) | 외부·내부 API, 이벤트, 오류 코드 |
| [`docs/security.md`](docs/security.md) | 인증·인가와 알려진 한계 |
| [`docs/engineering-notes.md`](docs/engineering-notes.md) | 구현 중 밟은 함정과 비자명한 동작 |
| [`docs/operations.md`](docs/operations.md) | 셋업, 실행, 부하 테스트, 배포 |
| [`docs/notes/`](docs/notes/) | Day별 구현 기록 (D1~D21) |
| [`docs/tracking/`](docs/tracking/) | 진행 상황, 결정 기록, 미해결 문제 |

## 현재 한계

- GKE 배포는 Terraform·Helm·CD 코드까지만 있고, GCP 프로젝트를 만들지 않아 실제로 올리지는 않았다.
- 알림(Alertmanager)과 로그 수집(Loki)이 없다.
- 미해결 문제는 [`docs/tracking/findings.md`](docs/tracking/findings.md)에 있다. 예: 차순위 승계가 결제에 실패한 입찰자의 다른 입찰을 다시 고를 수 있다, Outbox 발행기에서 영구 실패 행 하나가 뒤의 이벤트를 막는다.
