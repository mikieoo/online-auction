# D5: gRPC 전환 — Feign REST를 Proto로 바꾸기

## 오늘 한 일

auction-service → bid-service 낙찰 확정 호출 1구간을 **Feign REST에서 gRPC로 전환**했다. proto 파일을 정의하고, bid-service에 gRPC 서버를, auction-service에 gRPC 클라이언트를 붙였다. Circuit Breaker도 gRPC에 맞게 재연동했다.

```
auction-service ──gRPC(ConfirmWinner)──→ bid-service:9082
       │                                     │
       └─ CircuitBreaker.decorateSupplier()   └─ @GrpcService
       └─ discovery:///bid-service (Eureka)
```

실기동에서 Happy Path(gRPC 정산 성공)와 장애 복구(bid-service kill → CB OPEN → 재시작 → 정산 완료)를 모두 확인했다.

---

## 1. gRPC가 REST 대신 쓸모 있는 이유

MSA에서 서비스 간 내부 호출은 클라이언트가 볼 일이 없다. REST의 장점(사람이 읽기 쉬움, curl 테스트)은 내부에서 크게 의미가 없다.

| | REST (Feign) | gRPC |
|---|---|---|
| 직렬화 | JSON (텍스트) | Protobuf (바이너리) |
| 스키마 | 없음 (암묵적) | `.proto` 파일이 계약 |
| 코드 생성 | 수동 DTO 동기화 | 자동 생성 (컴파일 타임 검증) |
| 성능 | HTTP/1.1, 파싱 오버헤드 | HTTP/2, 다중화, 작은 페이로드 |
| 스트리밍 | 불편 | 네이티브 (server/client/bidirectional) |

이 프로젝트에서 체감한 것: **proto가 스키마 역할**을 해서, 양쪽 DTO를 수동으로 맞추던 작업이 사라졌다.

---

## 2. Proto 정의와 코드 생성

### proto 파일 (`common/src/main/proto/bid_winner.proto`)

```protobuf
syntax = "proto3";
package auction.grpc;
option java_package = "com.auction.common.grpc";
option java_multiple_files = true;

service BidWinnerService {
  rpc ConfirmWinner (ConfirmWinnerRequest) returns (ConfirmWinnerResponse);
}

message ConfirmWinnerRequest {
  int64 auction_id = 1;
}

message ConfirmWinnerResponse {
  int64 auction_id = 1;
  bool has_bids = 2;
  WinningBid winning_bid = 3;
}

message WinningBid {
  int64 bid_id = 1;
  int64 auction_id = 2;
  int64 bidder_id = 3;
  string amount = 4;
  string status = 5;
  string created_at = 6;
}
```

`amount`를 `string`으로 한 이유: proto3의 `double`은 부동소수점이라 금액에 부적합. Java 쪽에서 `BigDecimal.toPlainString()` ↔ `new BigDecimal(string)`으로 변환한다.

### Gradle protobuf 플러그인 (`common/build.gradle`)

```groovy
plugins {
    id 'java-library'
    id 'com.google.protobuf' version '0.9.4'
}

ext {
    grpcVersion = '1.63.0'   // starter와 반드시 일치
    protocVersion = '3.25.3'
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${protocVersion}" }
    plugins { grpc { artifact = "io.grpc:protoc-gen-grpc-java:${grpcVersion}" } }
    generateProtoTasks { all()*.plugins { grpc {} } }
}
```

`./gradlew :common:generateProto` 실행하면 `build/generated/source/proto/main/` 아래에 Java 클래스가 생성된다.

---

## 3. gRPC 서버 (bid-service)

```java
@GrpcService
public class BidWinnerGrpcService extends BidWinnerServiceGrpc.BidWinnerServiceImplBase {

    @Override
    public void confirmWinner(ConfirmWinnerRequest request,
                              StreamObserver<ConfirmWinnerResponse> responseObserver) {
        try {
            WinnerResponse result = bidService.confirmWinner(request.getAuctionId());
            // ... proto 빌더로 응답 구성
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(
                Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }
}
```

**주의할 점:** proto3의 `int64`는 기본값이 0이다. JPA 엔티티에서 `bidId`가 null(아직 persist 전)이면 `setBidId(null)` 호출 시 NPE가 난다. null 체크 필수.

---

## 4. gRPC 클라이언트 + Circuit Breaker (auction-service)

Feign은 `@CircuitBreaker` 어노테이션으로 자동 연동되지만, gRPC는 수동으로 `CircuitBreaker.decorateSupplier()`를 감싸야 한다:

```java
@Component
public class BidGrpcClient {
    private final BidWinnerServiceGrpc.BidWinnerServiceBlockingStub stub;
    private final CircuitBreaker circuitBreaker;

    public WinnerResponse confirmWinner(Long auctionId) {
        Supplier<WinnerResponse> decorated = CircuitBreaker
            .decorateSupplier(circuitBreaker, () -> {
                ConfirmWinnerResponse response = stub
                    .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                    .confirmWinner(request);
                return toWinnerResponse(response);
            });
        return decorated.get();
    }
}
```

- **`@GrpcClient("bid-service")`**: `grpc-client-spring-boot-starter`가 Eureka에서 `bid-service` 인스턴스를 찾아 채널을 만들어 준다.
- **`discovery:///bid-service`**: Eureka 연동 주소 스킴. yml에 `negotiation-type: plaintext` 필요.
- **`withDeadlineAfter`**: gRPC의 타임아웃. Feign의 `readTimeout`에 대응.

---

## 5. Gradle 의존성 격리 — 삽질 기록

### 문제: Gateway에서 `ClassNotFoundException: NettyChannelBuilder`

common 모듈의 gRPC 의존성을 `api`로 선언하면, common을 의존하는 **모든** 모듈에 gRPC가 전이된다. gateway는 gRPC를 쓰지 않는데 클래스패스에 gRPC jar가 들어가면서 Spring Boot 자동 설정이 꼬였다.

### 해결

1. common의 gRPC 의존성을 `implementation`으로 변경 (전이 차단)
2. gRPC를 실제 쓰는 auction-service, bid-service에서만 직접 선언
3. root `build.gradle`에서 `implementation project(':common')`을 3개 서비스에만 적용

```groovy
// root build.gradle
configure(subprojects.findAll { it.name in ['auction-service', 'bid-service', 'payment-service'] }) {
    dependencies {
        implementation project(':common')
    }
}
```

### 문제: `ClassNotFoundException: InternalGlobalInterceptors`

`grpc-spring-boot-starter:3.1.0.RELEASE`가 내부적으로 `grpc-core:1.63.0`을 쓰는데, 명시 의존성을 `1.64.0`으로 선언하면 API가 안 맞는다. **starter의 gRPC 버전과 반드시 일치시켜야 한다.**

---

## 6. 실기동 테스트 결과

### Happy Path

토큰 → 상품 등록 → 경매 시작(10초 만료) → 입찰 → 만료 대기 → 스케줄러가 gRPC `ConfirmWinner` 호출 → 낙찰자 확정 → auction **COMPLETED**.

### Circuit Breaker 장애 복구

| 단계 | CB 상태 | auction2 |
|------|---------|----------|
| bid-service kill | OPEN (3/3 실패, 100%) | CLOSED, winnerId=null |
| 스케줄러 재시도 | OPEN → 호출 차단 | 변동 없음 |
| bid-service 재시작 | HALF_OPEN → 시험 성공 | COMPLETED, winnerId=3 |

로그에서 확인한 흐름:
```
gRPC UNAVAILABLE: No servers found for bid-service   ← Eureka에서 사라짐
정산 중 외부 서비스 호출 실패, 다음 주기에 재시도   ← 개별 실패
서킷 브레이커 OPEN, 이번 틱의 남은 정산을 중단     ← CB 열림
```

---

## 면접에서 물어볼 만한 것

**Q: gRPC의 단점은?**
- 바이너리라 curl로 디버깅 불가 (grpcurl, Postman gRPC 등 별도 도구 필요)
- proto 파일 변경 시 양쪽 재빌드 필요 (REST는 필드 추가가 자유로움)
- 브라우저에서 직접 호출 불가 (gRPC-Web 프록시 필요)
- 그래서 **외부 API는 REST, 내부 호출은 gRPC**가 일반적

**Q: proto3에서 필드 기본값(0, "", false)과 "값을 안 보냄"을 어떻게 구분?**
- proto3는 구분 불가 (기본값 = 미설정). 구분이 필요하면 `optional` 키워드를 쓰거나, wrapper 타입(`google.protobuf.Int64Value`) 사용.
- 이 프로젝트에서는 `bidId`가 0이면 "아직 저장 전"이 아니라 "id가 0"으로 보이므로, 서버 쪽에서 null 체크 후 set 여부를 결정했다.

**Q: gRPC Circuit Breaker를 Feign처럼 자동 연동할 수 없나?**
- `grpc-spring-boot-starter`의 `ClientInterceptor`에 CB 로직을 넣을 수 있다. 이 프로젝트에서는 호출 지점이 1곳이라 `decorateSupplier` 수동 래핑으로 충분했다. 호출 지점이 많아지면 인터셉터로 추출하는 게 맞다.
