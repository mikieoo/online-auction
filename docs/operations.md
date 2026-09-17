# 셋업·빌드·배포

## 사전 요구

- JDK 17+ (현재 17/21 사용 중, sourceCompatibility 17)
- Docker + Docker Compose (Docker Desktop이 켜져 있어야 한다)
- Git

## 로컬 셋업

```bash
# 1. MySQL 등 인프라 기동 (최초 실행 시 DB 생성 + 스키마 적용 자동 수행)
docker compose up -d

# 2. 빌드 확인 (Docker 없이도 통과해야 한다)
./gradlew clean build

# 3. 실행 순서: discovery → 서비스 → gateway (MySQL이 떠 있어야 서비스의 JPA 기동 가능)
./gradlew :discovery-service:bootRun          # Eureka 8761. 먼저 띄운다
./gradlew :auction-service:bootRun
./gradlew :bid-service:bootRun
./gradlew :payment-service:bootRun
# gateway는 서명 키가 없으면 기동에 실패한다. local 프로필이어야 /auth/token이 생긴다
JWT_SECRET='32바이트-이상의-임의-문자열-0123456789' ./gradlew :gateway:bootRun --args='--spring.profiles.active=local'
```

순서가 어긋나도 기동은 되지만(서비스는 Eureka 등록을 계속 재시도한다), discovery가 뜨기 전에는 서비스 간 호출과 Gateway 라우팅이 503이다. 등록·조회 전파에 5~15초가 걸리므로 기동 직후 첫 요청이 503이면 잠시 뒤 다시 시도한다.

discovery 없이 서비스 하나만 띄워 개발할 때는 호출 대상 주소를 고정한다: `--clients.auction-service.url=http://localhost:8081` (auction-service라면 `clients.bid-service.url`, `clients.payment-service.url`). 이 값을 주면 Eureka 조회 대신 그 주소를 쓴다.

MySQL이 기동되지 않은 상태에서 서비스를 실행하면 DataSource 연결 실패로 기동이 중단된다. 반드시 `docker compose up -d`를 먼저 실행한다. auction-service만 띄우면 마감(CLOSED)은 되지만 정산은 bid/payment 호출 실패로 매 주기 재시도 로그만 남는다.

포트 8080이 이미 사용 중이면(예: Oracle XE의 TNS 리스너가 8080을 점유) Gateway 기동이 "Port 8080 was already in use"로 실패한다. `--server.port=8090`처럼 다른 포트로 띄운다.

포트 3306이 이미 사용 중(Windows MySQL80 서비스 등)이면 `docker compose up`이 실패한다. 서비스를 멈추거나, 컨테이너 포트를 3307로 바꾸고 서비스 기동 시 `SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3307/{db}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul` 환경 변수로 덮어쓴다.

## 서비스 포트

| 서비스 | 포트 |
|--------|------|
| gateway | 8080 |
| discovery-service (Eureka) | 8761 |
| auction-service | 8081 |
| bid-service | 8082 |
| payment-service | 8083 |
| MySQL | 3306 |

## 주요 설정값 (application.yml)

| 키 | 기본 | 서비스 | 의미 |
|---|---|---|---|
| `JWT_SECRET`(→ `gateway.jwt.secret`) | 없음(필수, 32바이트 이상) | gateway | JWT 서명 키. 없으면 기동 실패 |
| `gateway.jwt.ttl` | `PT1H` | gateway | 토큰 수명 |
| `eureka.client.service-url.defaultZone` | `http://localhost:8761/eureka` | 4개 앱 | Eureka 서버 주소 |
| `clients.{서비스}.url` | 없음(= Eureka로 조회) | auction, bid | 값을 주면 그 고정 주소로 호출(discovery 없이 단독 실행용) |
| `resilience4j.circuitbreaker.configs.default.*` | window 10, 최소 5회, 50%, OPEN 10초, HALF_OPEN 3회 | gateway, auction, bid | 브레이커 임계값 |
| `resilience4j.timelimiter.configs.default.timeout-duration` | 8s | gateway | Gateway 라우트 타임아웃. 입찰은 내부에서 상류를 최대 7초 기다리므로 그보다 길어야 한다 |
| `clients.*.connect-timeout-ms` / `read-timeout-ms` | 2000 / 5000 | 전부 | Feign 타임아웃 |
| `bid.increment` | 1000 | bid | 입찰 증가 단위(원) |
| `scheduler.auction.fixed-delay` | 5000 | auction | 마감·정산 주기(ms) |
| `scheduler.auction.settlement-batch-size` | 50 | auction | 한 주기에 정산할 최대 경매 수. 이 값 × Feign 타임아웃 합이 ShedLock lockAtMostFor(30초)를 넘지 않게 |
| `payment.simulation.failure-suffix` | `"9999"` | payment | 금액 정수부가 이 문자열로 끝나면 결제 실패 |

## 브레이커·등록 상태 확인

```bash
curl localhost:8080/actuator/circuitbreakers     # gateway: auction-service / bid-service / payment-service
curl localhost:8081/actuator/circuitbreakers     # auction-service: bid-service / payment-service
curl localhost:8082/actuator/circuitbreakers     # bid-service: auction-service
curl -H "Accept: application/json" localhost:8761/eureka/apps    # 등록된 인스턴스
```

서비스를 죽이면 해당 브레이커가 `OPEN`(호출 차단) → 10초 뒤 `HALF_OPEN`(시험 호출 3회) → 성공 시 `CLOSED`로 돌아온다. `HALF_OPEN`은 시험 호출이 들어와야 다음 상태로 넘어간다.

## MySQL 접속

```bash
docker exec -it auction-mysql mysql -u root -proot
```

## DB 초기화 (스키마 재적용)

초기화 스크립트는 데이터 볼륨이 비어 있을 때만 실행된다. **스키마 파일을 바꿨으면 반드시** 볼륨을 삭제하고 다시 올린다:
```bash
docker compose down -v
docker compose up -d
```

`-v` 없이 down하면 볼륨이 유지되어 기존 데이터가 보존되지만 스키마 변경은 반영되지 않는다. 확인: `docker exec auction-mysql mysql -uroot -proot -e "SHOW TABLES FROM auction_db"`에 `shedlock`이 보여야 한다.

## 수동 시나리오 확인

전부 띄운 뒤 Gateway(8080)로 호출한다 (Windows Git Bash에서는 한글 본문을 UTF-8 파일로 만들어 `--data-binary @file`로 보낸다):

```bash
G=localhost:8080
T1=$(curl -s -X POST $G/auth/token -H "Content-Type: application/json" -d '{"userId":1}' | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
T2=$(curl -s -X POST $G/auth/token -H "Content-Type: application/json" -d '{"userId":2}' | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
curl -X POST $G/api/v1/products -H "Authorization: Bearer $T1" -H "Content-Type: application/json" -d '{"name":"item","description":"d","startingPrice":10000}'
curl -X POST $G/api/v1/auctions -H "Authorization: Bearer $T1" -H "Content-Type: application/json" -d '{"productId":1,"endTime":"2026-09-17T12:00:30"}'
curl -X PATCH $G/api/v1/auctions/1/start -H "Authorization: Bearer $T1"
curl -X POST $G/api/v1/bids -H "Authorization: Bearer $T2" -H "Content-Type: application/json" -d '{"auctionId":1,"amount":10000}'
# endTime 경과 후 최대 15초 안에
curl $G/api/v1/auctions/1                                   # status=COMPLETED, winnerId=2 (공개 조회)
curl "$G/api/v1/bids?auctionId=1"                           # 해당 입찰 status=WINNER
curl $G/api/v1/payments/1 -H "Authorization: Bearer $T2"    # status=COMPLETED
```

서비스를 포트로 직접 부를 때는 토큰 대신 `-H "X-User-Id: 1"`을 넣는다(개발용).

낙찰가가 `...9999`원이면 결제가 FAILED로 기록되고 경매는 CLOSED에 남는다.

## 빌드

```bash
./gradlew clean build    # 전체 빌드 + 단위 테스트 (Docker 불필요)
./gradlew :모듈명:build   # 개별 모듈 빌드
```

빌드 캐시·병렬 빌드가 `gradle.properties`로 켜져 있다. 캐시 문제가 의심되면 `./gradlew clean build --no-build-cache`.

## 작업 종료

- 로컬: 서비스 프로세스 종료 후 `docker compose down` (데이터 유지) 또는 `down -v` (초기화).
- GKE(D18 이후): 워크로드를 0으로 내린다.
