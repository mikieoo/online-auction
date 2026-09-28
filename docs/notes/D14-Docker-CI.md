# D14 — Docker 컨테이너화 + GitHub Actions CI

## 왜 필요한가

D13까지 모든 서비스를 로컬 JVM으로 실행했다. K8s/GKE 배포의 전제 조건:
1. 서비스를 Docker 이미지로 빌드해야 K8s가 실행할 수 있음
2. CI 파이프라인이 있어야 push마다 빌드·테스트가 자동으로 돌아감

## Dockerfile — Multi-stage Build

```
Stage 1 (builder): gradle:8.10-jdk17
 └ gradle :서비스:bootJar --no-daemon -x test

Stage 2 (runtime): eclipse-temurin:17-jre
 └ COPY --from=builder *.jar → java -jar
```

### 왜 Multi-stage인가

- 빌드 도구(Gradle, JDK 전체, 소스코드)가 런타임 이미지에 포함되지 않음
- 공격 표면 감소 + 이미지 크기 절감
- 빌드 환경과 실행 환경 분리

### 왜 alpine이 아닌가

- `eclipse-temurin:17-jre-alpine`은 arm64(Apple Silicon) 미지원
- CI(ubuntu, x86_64)와 로컬(macOS, arm64) 양쪽에서 빌드 가능해야 함
- `eclipse-temurin:17-jre`는 multi-platform 지원

### 모노레포에서의 빌드 컨텍스트

빌드 컨텍스트는 프로젝트 루트 (common 모듈 의존성 때문):
```bash
docker build -f auction-service/Dockerfile -t auction-service:latest .
```

`.dockerignore`로 .git, build 산출물, docs 등 제외.

## Docker Compose — 전체 시스템 로컬 기동

기존 인프라(MySQL, Redis, Kafka, Zipkin) + Java 서비스 5개를 한 번에 기동:
```bash
docker compose up --build
```

### Kafka 듀얼 리스너

```
INTERNAL://auction-kafka:29092  ← Docker 내부 (Java 서비스)
EXTERNAL://localhost:9092       ← 호스트 (로컬 JVM 개발)
```

Docker 안의 Java 서비스는 `auction-kafka:29092`로 접속.
호스트에서 직접 띄운 서비스는 기존대로 `localhost:9092`.

### application-docker.yml

`SPRING_PROFILES_ACTIVE=docker`로 활성화. localhost를 컨테이너 이름으로 오버라이드:
- `localhost:3306` → `auction-mysql:3306`
- `localhost:9092` → `auction-kafka:29092`
- `localhost:6379` → `auction-redis:6379`
- `localhost:8761` → `discovery-service:8761`

### 서비스 기동 순서 (depends_on + healthcheck)

```
mysql(healthy) + kafka(healthy) + redis(healthy)
        ↓
discovery-service(healthy)
        ↓
auction-service, bid-service, payment-service
        ↓
gateway
```

`condition: service_healthy`로 인프라가 준비된 후에만 서비스가 시작됨.

## GitHub Actions CI

```yaml
jobs:
  build-and-test:     # ./gradlew clean build
  docker-build:       # 5개 서비스 × docker build (matrix)
```

- `gradle/actions/setup-gradle@v4`: Gradle 캐싱 포함
- `docker-build`는 `build-and-test` 성공 후 실행 (needs)
- matrix strategy로 5개 서비스를 병렬 빌드

## 면접 포인트

1. **왜 Multi-stage build인가** — 런타임에 빌드 도구 불필요, 이미지 크기·보안
2. **모노레포 Docker 빌드** — 빌드 컨텍스트를 루트로, .dockerignore로 최소화
3. **Kafka 듀얼 리스너** — Docker 내부와 호스트 양쪽에서 접근 가능하게
4. **CI에서 Gradle 캐싱** — `setup-gradle` 액션이 `~/.gradle/caches` 자동 캐싱
5. **healthcheck + depends_on** — 컨테이너 기동 순서 보장, 인프라 준비 전 서비스 시작 방지
