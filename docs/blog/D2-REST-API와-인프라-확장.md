# D2: REST API 구현 + Docker Compose 인프라 확장

## 오늘 한 일

auction-service의 상품 등록 / 경매 생성 / 경매 시작 REST API를 구현하고, bid-service와 payment-service의 도메인 뼈대를 만들었다. Docker Compose에는 Redis, Kafka, Zipkin, Kafka UI를 추가해서 앞으로 쓸 인프라를 미리 준비했다.

---

## 1. JPA 엔티티 설계

### Product — 불변 엔티티

```java
@Entity
@Table(name = "product")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_id")
    private Long productId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "starting_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal startingPrice;

    // ...

    protected Product() {}  // JPA 전용

    public Product(Long sellerId, String name, String description, BigDecimal startingPrice) {
        this.sellerId = sellerId;
        this.name = name;
        this.description = description;
        this.startingPrice = startingPrice;
        this.createdAt = LocalDateTime.now();
    }
}
```

설계 포인트:

- **`protected` 기본 생성자:** JPA는 리플렉션으로 엔티티를 생성하기 때문에 기본 생성자가 반드시 필요하다. `protected`로 선언해서 외부에서 빈 객체를 직접 만드는 것을 방지한다. `private`은 프록시 생성 문제가 생길 수 있어 `protected`가 관례.
- **setter 없음:** Product는 한 번 등록되면 변경할 이유가 없다. setter를 없애서 불변성을 보장. 값이 필요하면 생성자에서 한 번에 세팅.
- **`@GeneratedValue(IDENTITY)`:** MySQL의 AUTO_INCREMENT를 사용. `SEQUENCE` 전략은 MySQL에서 지원하지 않고, `TABLE` 전략은 성능이 떨어진다. IDENTITY의 단점은 `persist()` 시점에 즉시 INSERT가 발생해서 쓰기 지연(write-behind)을 못 쓴다는 것인데, 이 프로젝트에서는 문제 없다.
- **`BigDecimal` 금액:** `double`이나 `float`는 부동소수점 오차가 있어 금액 계산에 부적합. `DECIMAL(15,2)`와 매핑되는 `BigDecimal`을 사용.

### Auction — 상태 전이를 가진 엔티티

```java
@Entity
@Table(name = "auction")
public class Auction {

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AuctionStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    // ...
}
```

#### 상태 머신 (State Machine)

경매의 생명주기를 enum으로 관리한다:

```
WAITING → ACTIVE → CLOSED → COMPLETED
                         → FAILED
```

```java
public enum AuctionStatus {
    WAITING,    // 경매 생성됨, 아직 시작 전
    ACTIVE,     // 경매 진행 중, 입찰 가능
    CLOSED,     // 경매 마감, 낙찰자 확정 대기
    COMPLETED,  // 결제 완료, 경매 종료
    FAILED      // 결제 실패 (3회 차순위 승계 후에도 실패)
}
```

상태 전이 로직을 **엔티티 안에** 둔다:

```java
public void start() {
    if (this.status != AuctionStatus.WAITING) {
        throw new IllegalStateException(
                "경매를 시작할 수 없습니다. 현재 상태: " + this.status);
    }
    this.status = AuctionStatus.ACTIVE;
    this.startTime = LocalDateTime.now();
    this.updatedAt = this.startTime;
}

public void close() {
    if (this.status != AuctionStatus.ACTIVE) {
        throw new IllegalStateException(
                "경매를 마감할 수 없습니다. 현재 상태: " + this.status);
    }
    this.status = AuctionStatus.CLOSED;
    this.updatedAt = LocalDateTime.now();
}
```

**왜 서비스가 아니라 엔티티에서 상태를 전이하는가?**

도메인 주도 설계(DDD)의 **Rich Domain Model** 패턴이다. 상태 전이의 규칙(WAITING에서만 시작 가능, ACTIVE에서만 마감 가능)은 비즈니스 규칙이므로 도메인 객체가 스스로 보호하는 게 맞다. 서비스에서 `auction.setStatus(ACTIVE)`를 하면 어디서든 상태를 바꿀 수 있어서 불변식이 깨진다.

```java
// 나쁜 예: Anemic Domain Model
// 서비스에서 직접 상태 변경 — 규칙이 서비스에 흩어짐
auction.setStatus(AuctionStatus.ACTIVE);
auction.setStartTime(LocalDateTime.now());

// 좋은 예: Rich Domain Model
// 엔티티가 자기 규칙을 지킴
auction.start(); // 내부에서 상태 검증 + 전이
```

#### `@Version` — 낙관적 락

```java
@Version
@Column(name = "version", nullable = false)
private Long version;
```

JPA의 `@Version`은 **낙관적 락(Optimistic Locking)**을 구현한다. 동작 방식:

1. 엔티티를 읽을 때 version 값(예: 0)을 함께 가져옴
2. UPDATE 쿼리에 `WHERE version = 0`이 자동 추가됨
3. 두 트랜잭션이 동시에 같은 엔티티를 수정하면, 먼저 커밋한 쪽이 version을 1로 올림
4. 늦게 커밋하는 쪽은 `WHERE version = 0`에 매칭되는 행이 없으므로 `OptimisticLockException` 발생

```sql
-- JPA가 생성하는 실제 SQL
UPDATE auction SET status='ACTIVE', version=1, ...
WHERE auction_id=1 AND version=0
```

경매 시스템에서 중요한 이유: 마감 시점에 최고 입찰자를 낙찰자로 지정하는 과정에서 동시 접근이 발생할 수 있다. 락 없이 처리하면 두 번 낙찰되는 문제가 생길 수 있다. D6에서 분산 락(Redis)과 비교 실험을 할 예정.

#### `@Enumerated(EnumType.STRING)` vs `ORDINAL`

```java
@Enumerated(EnumType.STRING)
private AuctionStatus status;
```

`EnumType.ORDINAL`은 enum의 순서(0, 1, 2, ...)를 DB에 저장한다. enum 중간에 새 값을 추가하면 기존 데이터의 의미가 바뀌는 치명적 문제가 있다:

```java
// 처음: WAITING(0), ACTIVE(1), CLOSED(2)
// 나중에 PAUSED를 추가하면?
// WAITING(0), PAUSED(1), ACTIVE(2), CLOSED(3)
// → DB에 1로 저장된 ACTIVE가 갑자기 PAUSED로 해석됨
```

`EnumType.STRING`은 "WAITING", "ACTIVE" 같은 문자열을 저장해서 이 문제가 없다. 저장 공간이 약간 더 들지만 안전성이 훨씬 높다.

### Bid — 입찰 엔티티

```java
@Entity
@Table(name = "bid")
public class Bid {

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Bid() {}

    public Bid(Long auctionId, Long bidderId, BigDecimal amount) {
        this.auctionId = auctionId;
        this.bidderId = bidderId;
        this.amount = amount;
        this.status = BidStatus.ACTIVE;
        this.createdAt = LocalDateTime.now();
    }

    public void markWinning() { this.status = BidStatus.WINNING; }
    public void markOutbid()  { this.status = BidStatus.OUTBID; }
    public void cancel()      { this.status = BidStatus.CANCELLED; }
}
```

- **`updatable = false`:** `createdAt`은 생성 시 한 번 기록되면 절대 변경되지 않는다. JPA의 UPDATE SQL에서 이 컬럼이 빠진다.
- **상태 전이 메서드:** `markWinning()`, `markOutbid()`, `cancel()` — Auction과 마찬가지로 setter 대신 의미 있는 메서드명으로 상태를 변경한다.

### Payment — 멱등성 키를 가진 엔티티

```java
@Entity
@Table(name = "payment")
public class Payment {

    @Column(nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    public void complete() {
        if (this.status != PaymentStatus.PENDING) {
            throw new IllegalStateException("PENDING 상태에서만 완료 처리할 수 있습니다.");
        }
        this.status = PaymentStatus.COMPLETED;
        this.updatedAt = LocalDateTime.now();
    }

    public void fail(String reason) {
        if (this.status != PaymentStatus.PENDING) {
            throw new IllegalStateException("PENDING 상태에서만 실패 처리할 수 있습니다.");
        }
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
        this.updatedAt = LocalDateTime.now();
    }
}
```

- **멱등성 키:** Kafka의 at-least-once 전달 보장으로 같은 메시지가 여러 번 올 수 있다. `idempotencyKey`에 UNIQUE 제약을 걸어서 같은 키로 두 번째 결제 생성을 시도하면 DB 레벨에서 거부된다. 키 형식은 `{auctionId}-{winnerId}-{reassignmentCount}`.
- **상태 검증:** `complete()`와 `fail()` 모두 PENDING 상태에서만 호출 가능. 이미 완료된 결제를 다시 완료하거나, 실패한 결제를 또 실패 처리하는 버그를 방지.

---

## 2. Repository 계층

### Spring Data JPA — 인터페이스만으로 CRUD 완성

```java
public interface ProductRepository extends JpaRepository<Product, Long> {
}
```

`JpaRepository<Product, Long>`을 상속하는 것만으로 `save()`, `findById()`, `findAll()`, `delete()` 등이 자동 구현된다. Spring Data JPA가 런타임에 프록시 구현체를 생성해서 주입한다.

### 쿼리 메서드 — 메서드명으로 쿼리 생성

```java
public interface AuctionRepository extends JpaRepository<Auction, Long> {
    boolean existsByProductIdAndStatus(Long productId, AuctionStatus status);
    List<Auction> findByStatusAndEndTimeBefore(AuctionStatus status, LocalDateTime time);
}
```

Spring Data JPA의 **쿼리 메서드(Query Method)** 기능이다. 메서드 이름의 규칙에 따라 자동으로 JPQL을 생성한다:

- `existsByProductIdAndStatus` → `SELECT COUNT(*) > 0 FROM auction WHERE product_id = ? AND status = ?`
- `findByStatusAndEndTimeBefore` → `SELECT ... FROM auction WHERE status = ? AND end_time < ?`

접두사 규칙:
- `findBy`: SELECT 쿼리, 결과 반환
- `existsBy`: COUNT 쿼리, boolean 반환 (전체 결과를 가져오지 않아서 findBy보다 효율적)
- `countBy`: COUNT 쿼리, long 반환

키워드:
- `And` / `Or`: WHERE 조건 결합
- `Before` / `After`: 날짜 비교 (`<`, `>`)
- `OrderBy...Desc`: 정렬

```java
public interface BidRepository extends JpaRepository<Bid, Long> {
    List<Bid> findByAuctionIdOrderByAmountDesc(Long auctionId);
    Optional<Bid> findFirstByAuctionIdOrderByAmountDesc(Long auctionId);
}
```

- `findFirstBy...OrderByAmountDesc`: `LIMIT 1`과 동일. 최고 입찰을 하나만 가져온다.
- `Optional` 반환: 결과가 없을 수 있으므로 null 대신 Optional로 감싸서 NPE를 방지.

---

## 3. Service 계층 — 비즈니스 규칙 검증

### 상품 등록

```java
@Service
public class ProductService {

    @Transactional
    public Product createProduct(Long sellerId, CreateProductRequest request) {
        if (request.getStartingPrice().signum() <= 0) {
            throw new IllegalArgumentException("시작가는 0보다 커야 합니다.");
        }
        Product product = new Product(
                sellerId, request.getName(),
                request.getDescription(), request.getStartingPrice()
        );
        return productRepository.save(product);
    }
}
```

- **`BigDecimal.signum()`:** 양수면 1, 0이면 0, 음수면 -1 반환. `startingPrice > 0` 같은 비교를 BigDecimal에서는 이렇게 한다. `compareTo(BigDecimal.ZERO) > 0`도 같은 의미.
- **`@Transactional`:** 메서드 전체가 하나의 DB 트랜잭션으로 묶인다. 예외 발생 시 자동 롤백.

### 경매 생성 — 다중 검증

```java
@Transactional
public Auction createAuction(Long sellerId, CreateAuctionRequest request) {
    // 1. 상품 존재 확인
    var product = productRepository.findById(request.getProductId())
            .orElseThrow(() -> new IllegalArgumentException(
                    "상품을 찾을 수 없습니다. id=" + request.getProductId()));

    // 2. 본인 상품인지 확인
    if (!product.getSellerId().equals(sellerId)) {
        throw new IllegalArgumentException("본인의 상품만 경매에 등록할 수 있습니다.");
    }

    // 3. 종료 시간 검증
    if (request.getEndTime().isBefore(LocalDateTime.now())) {
        throw new IllegalArgumentException("종료 시간은 현재 시간 이후여야 합니다.");
    }

    // 4. 중복 경매 방지
    boolean hasActiveAuction = auctionRepository.existsByProductIdAndStatus(
            request.getProductId(), AuctionStatus.ACTIVE);
    if (hasActiveAuction) {
        throw new IllegalStateException(
                "해당 상품에 이미 진행 중인 경매가 있습니다.");
    }

    Auction auction = new Auction(request.getProductId(), request.getEndTime());
    return auctionRepository.save(auction);
}
```

검증 순서가 중요하다:
1. **존재 확인** → 없으면 이후 검증이 무의미
2. **권한 확인** → 남의 상품에 경매를 걸면 안 됨
3. **시간 검증** → 과거 시간으로 경매를 만들면 안 됨
4. **중복 방지** → 같은 상품에 두 개 이상의 활성 경매가 동시에 존재하면 안 됨

`@Transactional(readOnly = true)`를 조회 메서드에 붙이면:
- Hibernate가 dirty checking을 건너뛰어 성능 향상
- DB에 따라 읽기 전용 트랜잭션 최적화가 적용될 수 있음

---

## 4. Controller 계층 — REST API

### X-User-Id 헤더

```java
@PostMapping
public ResponseEntity<AuctionResponse> createAuction(
        @RequestHeader("X-User-Id") Long userId,
        @RequestBody CreateAuctionRequest request) {
    var auction = auctionService.createAuction(userId, request);
    return ResponseEntity.status(HttpStatus.CREATED)
            .body(AuctionResponse.from(auction));
}
```

**왜 `@RequestHeader`로 userId를 받는가?**

MSA에서 인증은 보통 **Gateway에서 처리**한다. 클라이언트가 JWT 토큰을 보내면 Gateway가 토큰을 검증하고, 내부 서비스에는 `X-User-Id` 헤더로 사용자 ID만 전달한다. 내부 서비스는 토큰 검증을 하지 않고 이 헤더만 신뢰한다.

```
Client → [JWT Token] → Gateway → [X-User-Id: 1] → auction-service
```

이 방식의 장점:
- 각 서비스에 인증 로직을 중복 구현할 필요 없음
- JWT 비밀키를 Gateway만 알면 됨
- 내부 서비스는 단순해짐

### DTO 변환 — `from()` 정적 팩토리

```java
public class AuctionResponse {
    private Long auctionId;
    private AuctionStatus status;
    // ...

    public static AuctionResponse from(Auction auction) {
        AuctionResponse response = new AuctionResponse();
        response.auctionId = auction.getAuctionId();
        response.status = auction.getStatus();
        // ...
        return response;
    }
}
```

**왜 엔티티를 직접 반환하지 않고 DTO로 변환하는가?**

1. **순환 참조:** 엔티티에 양방향 관계가 있으면 JSON 직렬화 시 무한 루프 발생
2. **민감 정보:** 엔티티에 `version` 같은 내부 필드가 노출됨
3. **API 안정성:** DB 스키마가 변경되어도 DTO를 유지하면 API 응답이 바뀌지 않음
4. **N+1 문제:** LazyLoading 프록시가 직렬화 시 예상치 못한 쿼리를 발생시킬 수 있음

`from()` 정적 팩토리 메서드를 쓰면 변환 로직이 DTO 클래스에 응집되어 관리하기 좋다.

### HTTP 상태 코드

```java
// 생성: 201 Created
return ResponseEntity.status(HttpStatus.CREATED).body(response);

// 조회/수정: 200 OK
return ResponseEntity.ok(response);
```

- **201 Created:** 새로운 리소스(상품, 경매)가 생성되었을 때
- **200 OK:** 기존 리소스를 조회하거나 수정(경매 시작)했을 때

REST API 설계에서 적절한 상태 코드를 쓰는 것은 클라이언트가 응답을 프로그래밍적으로 처리하는 데 중요하다.

### API 엔드포인트 정리

| Method | URL | 설명 | 요청 본문 |
|--------|-----|------|-----------|
| POST | `/api/v1/products` | 상품 등록 | name, description, startingPrice |
| GET | `/api/v1/products` | 상품 목록 조회 | - |
| GET | `/api/v1/products/{productId}` | 상품 단건 조회 | - |
| POST | `/api/v1/auctions` | 경매 생성 | productId, endTime |
| GET | `/api/v1/auctions` | 경매 목록 조회 | - |
| GET | `/api/v1/auctions/{auctionId}` | 경매 단건 조회 | - |
| PATCH | `/api/v1/auctions/{auctionId}/start` | 경매 시작 | - |

**왜 경매 시작이 PATCH인가?**

- `PUT`: 리소스 전체를 교체
- `PATCH`: 리소스의 일부를 수정

경매 시작은 status 필드만 바꾸는 부분 수정이므로 `PATCH`가 의미적으로 정확하다.

---

## 5. Docker Compose 확장

D1에서 MySQL만 있던 Docker Compose에 4개 서비스를 추가했다.

### 추가된 서비스

```yaml
services:
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    depends_on:
      - zookeeper
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1

  kafka-ui:
    image: provectuslabs/kafka-ui:latest
    depends_on:
      - kafka
    ports:
      - "9090:8080"

  zipkin:
    image: openzipkin/zipkin:latest
    ports:
      - "9411:9411"
```

### 각 서비스의 역할

| 서비스 | 포트 | 용도 | 사용 시점 |
|--------|------|------|-----------|
| Redis | 6379 | 분산 락(Redisson), 캐시 | D6 (동시성 제어) |
| Zookeeper | 2181 | Kafka 브로커 메타데이터 관리 | Kafka 의존성 |
| Kafka | 9092 | 서비스 간 비동기 이벤트 메시징 | D8 (이벤트 기반 통신) |
| Kafka UI | 9090 | Kafka 토픽/메시지 시각적 모니터링 | D8~ |
| Zipkin | 9411 | 분산 트레이싱 (요청 추적) | D22~ |

### Kafka 설정 핵심

```yaml
KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
```

`ADVERTISED_LISTENERS`는 **클라이언트가 브로커에 접속할 주소**다. Docker 네트워크 내부에서는 `kafka:9092`로 접근하지만, 로컬 Spring Boot 앱은 `localhost:9092`로 접근한다. 이 값이 잘못되면 Spring Boot에서 Kafka 연결이 안 된다.

```yaml
KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
```

로컬에서 브로커 1개만 쓰므로 replication factor를 1로 설정한다. 기본값이 3이라 1개 브로커로는 토픽 생성이 실패한다. 프로덕션에서는 최소 3으로 설정해야 데이터 유실을 방지할 수 있다.

### `depends_on`의 한계

```yaml
kafka:
  depends_on:
    - zookeeper
```

`depends_on`은 **컨테이너 시작 순서**만 보장하고, 서비스가 **실제로 준비(ready)됐는지**는 확인하지 않는다. Zookeeper 컨테이너가 시작됐지만 아직 포트를 열지 않은 상태에서 Kafka가 연결을 시도할 수 있다.

확실하게 하려면 `healthcheck` + `condition: service_healthy`를 사용해야 하지만, 로컬 환경에서는 보통 재시도 로직이 있어서 문제없다.

---

## 6. 검증 결과

```bash
# 전체 빌드
$ ./gradlew clean build
BUILD SUCCESSFUL in 9s

# 인프라 기동 (6개 컨테이너)
$ docker compose up -d
$ docker compose ps
# → mysql, redis, zookeeper, kafka, kafka-ui, zipkin 모두 Up

# API 테스트 — 상품 등록
$ curl -X POST localhost:8081/api/v1/products \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 1" \
  -d '{"name":"맥북 프로 M3","description":"2024년형","startingPrice":1500000}'
# → 201: {"productId":1, "sellerId":1, ...}

# API 테스트 — 경매 생성
$ curl -X POST localhost:8081/api/v1/auctions \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 1" \
  -d '{"productId":1,"endTime":"2026-09-20T18:00:00"}'
# → 201: {"auctionId":1, "status":"WAITING", ...}

# API 테스트 — 경매 시작
$ curl -X PATCH localhost:8081/api/v1/auctions/1/start \
  -H "X-User-Id: 1"
# → 200: {"auctionId":1, "status":"ACTIVE", "startTime":"2026-09-15T...", ...}
```

---

## 7. 프로젝트 구조 (D2 기준)

```
auction-platform/
├── docker-compose.yml              ← MySQL + Redis + Kafka + Zipkin
├── auction-service/
│   └── src/main/java/.../auction/
│       ├── domain/
│       │   ├── AuctionStatus.java  ← enum (5개 상태)
│       │   ├── Product.java        ← 불변 엔티티
│       │   └── Auction.java        ← 상태 전이 + @Version
│       ├── repository/
│       │   ├── ProductRepository.java
│       │   └── AuctionRepository.java
│       ├── dto/
│       │   ├── CreateProductRequest.java
│       │   ├── ProductResponse.java
│       │   ├── CreateAuctionRequest.java
│       │   └── AuctionResponse.java
│       ├── service/
│       │   ├── ProductService.java
│       │   └── AuctionService.java
│       └── controller/
│           ├── ProductController.java
│           └── AuctionController.java
├── bid-service/
│   └── src/main/java/.../bid/
│       ├── domain/
│       │   ├── BidStatus.java
│       │   └── Bid.java
│       └── repository/
│           └── BidRepository.java
└── payment-service/
    └── src/main/java/.../payment/
        ├── domain/
        │   ├── PaymentStatus.java
        │   └── Payment.java
        └── repository/
            └── PaymentRepository.java
```

---

## 면접 대비 포인트

**Q: Rich Domain Model vs Anemic Domain Model의 차이?**
→ Anemic은 엔티티가 getter/setter만 있고 비즈니스 로직은 서비스에 있다. Rich는 엔티티가 자신의 상태 전이 규칙을 직접 관리한다. 예: `auction.start()`가 WAITING 상태 검증과 ACTIVE 전이를 함께 수행. 장점은 불변식 보장, 단점은 엔티티가 무거워질 수 있다는 것.

**Q: 낙관적 락과 비관적 락의 차이?**
→ 낙관적 락은 충돌이 드물다고 가정하고, 커밋 시점에 version을 비교해서 충돌을 감지한다. DB 락을 잡지 않아서 성능이 좋지만, 충돌 시 재시도가 필요하다. 비관적 락은 `SELECT ... FOR UPDATE`로 행을 미리 잠가서 충돌 자체를 방지하지만, 대기 시간이 생긴다. 경매 시스템에서는 마감 시점에만 동시 접근이 몰리므로 낙관적 락이 적절하다.

**Q: 왜 엔티티를 직접 반환하지 않고 DTO로 변환하는가?**
→ API 응답의 안정성 (스키마 변경이 API에 영향 안 줌), 민감 정보 차단 (version, 내부 필드), 순환 참조 방지, N+1 쿼리 방지. `from()` 정적 팩토리로 변환 로직을 DTO에 응집시킨다.

**Q: `@Transactional(readOnly = true)`는 왜 쓰는가?**
→ Hibernate의 dirty checking을 비활성화해서 메모리와 CPU를 절약한다. DB에 따라 읽기 전용 최적화가 적용될 수 있다. 실수로 엔티티를 수정해도 flush가 안 되어 데이터 무결성을 지킬 수 있다.

**Q: Spring Data JPA의 쿼리 메서드는 어떻게 동작하는가?**
→ 런타임에 프록시 구현체를 생성한다. 메서드 이름을 파싱해서 JPQL로 변환하고, EntityManager를 통해 실행한다. 내부적으로 `SimpleJpaRepository`가 기본 CRUD를, `PartTreeJpaQuery`가 커스텀 쿼리 메서드를 처리한다.
