# D18 — 메트릭 모니터링 (Prometheus + Grafana)

## 왜 필요한가

D17의 트레이싱이 "개별 요청"의 흐름이라면, 메트릭은 "시스템 전체"의 건강 상태다.
- 초당 요청 수는 얼마나 되는가?
- 에러율이 갑자기 올랐는가?
- 응답 시간 p99가 어디까지 치솟았는가?

이런 집계 숫자는 로그나 트레이스로 알 수 없다. 관측성 3대 축 중 메트릭 영역.

## 핵심 개념

| 용어 | 설명 |
|---|---|
| Counter | 단조 증가 (요청 수, 에러 수) |
| Gauge | 오르내림 (활성 스레드, 커넥션 풀) |
| Histogram | 분포 (응답 시간 p50, p95, p99) |
| Scrape | Prometheus가 타겟에서 메트릭을 긁어오는 행위 |
| PromQL | Prometheus 쿼리 언어 |

## 구조

```
Spring Boot Actuator + micrometer-registry-prometheus
    ↓ /actuator/prometheus (Prometheus 포맷 노출)
Prometheus (pull 방식, 15초 간격 수집)
    ↓
Grafana (시각화 대시보드)
```

## 구현 내용

### 의존성 (root build.gradle)

```groovy
implementation 'io.micrometer:micrometer-registry-prometheus'
```

기존 actuator + micrometer-tracing에 추가. 코드 없이 의존성만으로 `/actuator/prometheus` 엔드포인트가 활성화된다.

### 설정 (application.yml, 모든 서비스)

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,...,prometheus
  metrics:
    tags:
      application: ${spring.application.name}
```

`application` 태그를 달아야 Grafana에서 서비스별 필터링이 가능하다.

### 자동 수집 메트릭 (코드 없이)

- `http_server_requests_seconds` — HTTP 요청 수 + 응답 시간 (method, uri, status)
- `jvm_memory_used_bytes` — JVM 힙/논힙
- `jvm_threads_live_threads` — 라이브 스레드 수
- `hikaricp_connections_active` — DB 커넥션 풀 활성/대기
- `resilience4j_circuitbreaker_state` — 브레이커 상태
- `kafka_consumer_*` — Kafka 컨슈머 메트릭

### Prometheus 설정 (`infra/prometheus/prometheus.yml`)

```yaml
scrape_configs:
  - job_name: 'auction-platform'
    metrics_path: /actuator/prometheus
    static_configs:
      - targets:
          - auction-service:8081
          - bid-service:8082
          - payment-service:8083
          - gateway:8080
          - discovery-service:8761
```

### Grafana 대시보드

프로비저닝으로 자동 로드:
- 데이터소스: Prometheus 자동 등록
- 대시보드: `auction-platform.json` 자동 로드

패널 구성:
1. **Request Rate** — 서비스별 초당 요청 수
2. **Error Rate (5xx)** — 서비스별 에러율
3. **Response Time p95/p99** — 응답 시간 분포
4. **JVM Heap** — 힙 메모리 사용량
5. **JVM Threads** — 라이브 스레드 수
6. **HikariCP** — DB 커넥션 활성/대기
7. **Circuit Breaker State** — 브레이커 상태 (CLOSED/OPEN/HALF_OPEN)

### Docker Compose

```yaml
prometheus:  # localhost:9091
grafana:     # localhost:3000 (admin/admin)
```

9090은 kafka-ui가 쓰고 있어서 Prometheus는 9091으로 매핑.

## Pull vs Push

| | Pull (Prometheus) | Push (StatsD, InfluxDB) |
|---|---|---|
| 방식 | 모니터링 서버가 타겟을 긁어감 | 서비스가 메트릭을 보냄 |
| 장점 | 타겟 추가가 설정만으로 가능, 서비스 코드 변경 없음 | 방화벽 뒤 서비스도 가능 |
| 단점 | 서비스가 네트워크에서 접근 가능해야 함 | 서비스마다 푸시 코드 필요 |

## 면접 포인트

1. **관측성 3대 축** — 메트릭(집계 숫자), 트레이싱(요청 흐름), 로깅(텍스트 이벤트)
2. **RED 메서드** — Rate, Errors, Duration — 서비스 모니터링 핵심
3. **USE 메서드** — Utilization, Saturation, Errors — 인프라 모니터링
4. **Cardinality** — 라벨 조합이 폭발하면 Prometheus 메모리 폭증. userId 같은 고유값은 라벨에 넣지 않는다
5. **Pull 방식** — 서비스가 늘어도 설정만 추가. 서비스 디스커버리와 연동하면 자동 발견도 가능
