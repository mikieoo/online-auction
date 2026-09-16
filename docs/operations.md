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

# 3. 서비스 실행 (MySQL이 떠 있어야 JPA 기동 가능). 세 서비스를 모두 띄워야 정산이 끝까지 진행된다.
./gradlew :auction-service:bootRun
./gradlew :bid-service:bootRun
./gradlew :payment-service:bootRun
./gradlew :gateway:bootRun
```

MySQL이 기동되지 않은 상태에서 서비스를 실행하면 DataSource 연결 실패로 기동이 중단된다. 반드시 `docker compose up -d`를 먼저 실행한다. auction-service만 띄우면 마감(CLOSED)은 되지만 정산은 bid/payment 호출 실패로 매 주기 재시도 로그만 남는다.

포트 3306이 이미 사용 중(Windows MySQL80 서비스 등)이면 `docker compose up`이 실패한다. 서비스를 멈추거나, 컨테이너 포트를 3307로 바꾸고 서비스 기동 시 `SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3307/{db}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul` 환경 변수로 덮어쓴다.

## 서비스 포트

| 서비스 | 포트 |
|--------|------|
| gateway | 8080 |
| auction-service | 8081 |
| bid-service | 8082 |
| payment-service | 8083 |
| MySQL | 3306 |

## 주요 설정값 (application.yml)

| 키 | 기본 | 서비스 | 의미 |
|---|---|---|---|
| `clients.auction-service.url` | `http://localhost:8081` | bid | 경매 조회 대상 |
| `clients.bid-service.url`, `clients.payment-service.url` | `http://localhost:8082`, `:8083` | auction | 낙찰 확정·결제 요청 대상 |
| `clients.*.connect-timeout-ms` / `read-timeout-ms` | 2000 / 5000 | 전부 | Feign 타임아웃 |
| `bid.increment` | 1000 | bid | 입찰 증가 단위(원) |
| `scheduler.auction.fixed-delay` | 5000 | auction | 마감·정산 주기(ms) |
| `scheduler.auction.settlement-batch-size` | 50 | auction | 한 주기에 정산할 최대 경매 수. 이 값 × Feign 타임아웃 합이 ShedLock lockAtMostFor(30초)를 넘지 않게 |
| `payment.simulation.failure-suffix` | `"9999"` | payment | 금액 정수부가 이 문자열로 끝나면 결제 실패 |

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

세 서비스를 띄운 뒤 (Windows Git Bash에서는 한글 본문을 UTF-8 파일로 만들어 `--data-binary @file`로 보낸다):

```bash
curl -X POST localhost:8081/api/v1/products -H "X-User-Id: 1" -H "Content-Type: application/json" -d '{"name":"item","description":"d","startingPrice":10000}'
curl -X POST localhost:8081/api/v1/auctions -H "X-User-Id: 1" -H "Content-Type: application/json" -d '{"productId":1,"endTime":"2026-09-17T12:00:30"}'
curl -X PATCH localhost:8081/api/v1/auctions/1/start -H "X-User-Id: 1"
curl -X POST localhost:8082/api/v1/bids -H "X-User-Id: 2" -H "Content-Type: application/json" -d '{"auctionId":1,"amount":10000}'
# endTime 경과 후 최대 10초 안에
curl localhost:8081/api/v1/auctions/1          # status=COMPLETED, winnerId=2
curl localhost:8082/api/v1/bids?auctionId=1    # 해당 입찰 status=WINNER
curl localhost:8083/api/v1/payments/1 -H "X-User-Id: 2"   # status=COMPLETED
```

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
