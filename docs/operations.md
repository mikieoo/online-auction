# 셋업·빌드·배포

## 사전 요구

- JDK 17+ (현재 21 사용 중, sourceCompatibility 17)
- Docker + Docker Compose
- Git

## 로컬 셋업

```bash
# 1. MySQL 기동 (최초 실행 시 DB 생성 + 스키마 적용 자동 수행)
docker compose up -d

# 2. 빌드 확인
./gradlew clean build

# 3. 개별 서비스 실행 (MySQL이 떠 있어야 JPA 기동 가능)
./gradlew :auction-service:bootRun
./gradlew :bid-service:bootRun
./gradlew :payment-service:bootRun
./gradlew :gateway:bootRun
```

MySQL이 기동되지 않은 상태에서 서비스를 실행하면 DataSource 연결 실패로 기동이 중단된다. 반드시 `docker compose up -d`를 먼저 실행한다.

## 서비스 포트

| 서비스 | 포트 |
|--------|------|
| gateway | 8080 |
| auction-service | 8081 |
| bid-service | 8082 |
| payment-service | 8083 |
| MySQL | 3306 |

## MySQL 접속

```bash
docker exec -it auction-mysql mysql -u root -proot
```

## DB 초기화 (스키마 재적용)

Docker 볼륨을 삭제하면 초기화 스크립트가 재실행된다:
```bash
docker compose down -v
docker compose up -d
```

`-v` 없이 down하면 볼륨이 유지되어 기존 데이터가 보존된다.

## 빌드

```bash
./gradlew clean build    # 전체 빌드 + 테스트
./gradlew :모듈명:build   # 개별 모듈 빌드
```
