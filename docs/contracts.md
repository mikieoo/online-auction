# 외부 인터페이스 계약

## 공통 규약 (REST)

- 공개 진입점은 Gateway `http://localhost:8080` 하나다. 아래 서비스별 표의 포트(8081~8083)는 서비스가 실제로 듣는 포트이며, 직접 호출은 개발·디버깅용이다.
- 경로: 공개 API는 `/api/v1/{복수 리소스}`, 서비스 간 내부 API는 `/internal/v1/...`. Gateway는 `/api/**`만 라우팅하며 `/internal/**`와 그 밖의 경로는 404 `NOT_FOUND`다.
- 인증(Gateway 경유): `Authorization: Bearer <JWT>`. 필요한 요청은 `/api/**`의 GET이 아닌 모든 메서드와 `GET /api/v1/payments/**`. 상품·경매·입찰 조회는 토큰 없이 가능. Gateway가 검증 후 `X-User-Id: {userId}`를 붙여 서비스에 전달하고, 클라이언트가 보낸 `X-User-Id`는 버린다. 서비스를 포트로 직접 호출할 때만 `X-User-Id`를 직접 넣는다(없으면 400). 내부 API는 헤더 없음(서비스 간 무인증).
- 본문: JSON. 금액은 number(소수 2자리, 원), 시각은 ISO-8601 `LocalDateTime`(예: `2026-09-16T23:16:38`), 식별자는 정수.
- 성공 상태: 생성 201, 조회·변경 200. 멱등 생성 API는 기존 건 반환 시 200.
- 오류 본문(세 서비스 동일):

```json
{ "code": "AUCTION_NOT_ACTIVE", "message": "진행 중인 경매에만 입찰할 수 있습니다. auctionId=7, 현재 상태: WAITING" }
```

| HTTP | 의미 | code |
|---|---|---|
| 400 | 값 검증 실패(필수 누락, 형식, 음수), 헤더 누락, 본문 파싱 실패 | `INVALID_REQUEST` |
| 401 | (Gateway) 토큰 없음·형식 오류·서명 불일치·sub 형식 오류 | `UNAUTHORIZED` |
| 401 | (Gateway) 토큰 만료 — 다시 발급받아야 한다 | `TOKEN_EXPIRED` |
| 400 | 입찰 금액 규칙 위반 | `BID_AMOUNT_TOO_LOW` |
| 403 | 판매자 본인 경매 입찰 | `SELLER_CANNOT_BID` |
| 403 | 타인 상품·경매·결제에 대한 요청 | `FORBIDDEN` |
| 404 | 리소스 없음 | `PRODUCT_NOT_FOUND`, `AUCTION_NOT_FOUND`, `BID_NOT_FOUND`, `PAYMENT_NOT_FOUND` |
| 404 | 매핑되지 않은 경로 | `NOT_FOUND` |
| 409 | 상태 위반 | `AUCTION_NOT_ACTIVE`, `AUCTION_ALREADY_ENDED`, `ACTIVE_AUCTION_EXISTS`, `INVALID_STATE_TRANSITION` |
| 502 | 상류 서비스가 예상 밖 4xx 또는 계약 위반 응답 | `UPSTREAM_ERROR` |
| 503 | 상류 서비스 연결 실패·타임아웃·5xx, 또는 상류 호출 브레이커가 열려 있음 | `UPSTREAM_UNAVAILABLE` |
| 503 | (Gateway) 대상 서비스에 연결할 수 없거나 브레이커가 열려 있음. message에 서비스 이름 포함 | `SERVICE_UNAVAILABLE` |
| 500 | 그 외 | `INTERNAL_ERROR` |

`message`는 한국어 설명이며 프로그램 분기에 쓰지 않는다. 분기는 `code`로 한다.

## gateway (8080)

| 메서드·경로 | 요청 | 성공 | 오류 |
|---|---|---|---|
| `POST /auth/token` | `{ "userId": 7 }` (양의 정수) | 200 `{ "accessToken": "<JWT>", "tokenType": "Bearer", "expiresIn": 3600 }` | 400 `INVALID_REQUEST`. `local` 프로필이 아니면 경로 자체가 404 `NOT_FOUND` |
| `/api/v1/products/**`, `/api/v1/auctions/**` | 아래 auction-service 표와 동일 | 하류 응답 그대로 | 401, 503 `SERVICE_UNAVAILABLE` + 하류 오류 그대로 |
| `/api/v1/bids/**` | 아래 bid-service 표와 동일 | 〃 | 〃 |
| `/api/v1/payments/**` | 아래 payment-service 표와 동일(GET도 토큰 필요) | 〃 | 〃 |

토큰: HS256, 클레임 `sub`(userId)·`iat`·`exp`, 수명 기본 1시간. 하류 서비스가 돌려준 상태 코드와 오류 본문은 Gateway가 바꾸지 않는다. `SERVICE_UNAVAILABLE`을 받은 클라이언트는 잠시 뒤 재시도한다 — 브레이커는 10초 뒤 시험 호출을 허용한다.

## auction-service (8081)

| 메서드·경로 | 헤더 | 요청 본문 | 성공 | 오류 |
|---|---|---|---|---|
| `POST /api/v1/products` | `X-User-Id` | `{ "name": "…", "description": "…", "startingPrice": 10000 }` (name 필수, startingPrice > 0) | 201 `ProductResponse` | 400 |
| `GET /api/v1/products/{productId}` | — | — | 200 `ProductResponse` | 404 |
| `GET /api/v1/products` | — | — | 200 `ProductResponse[]` | — |
| `POST /api/v1/auctions` | `X-User-Id` | `{ "productId": 1, "endTime": "2026-09-17T12:00:00" }` | 201 `AuctionResponse` | 400(endTime이 현재 이전), 403(타인 상품), 404, 409 `ACTIVE_AUCTION_EXISTS` |
| `PATCH /api/v1/auctions/{auctionId}/start` | `X-User-Id` | — | 200 `AuctionResponse` | 403, 404, 409 `INVALID_STATE_TRANSITION`(WAITING 아님) · `AUCTION_ALREADY_ENDED`(endTime 경과) · `ACTIVE_AUCTION_EXISTS` |
| `GET /api/v1/auctions/{auctionId}` | — | — | 200 `AuctionResponse` | 404 |
| `GET /api/v1/auctions` | — | — | 200 `AuctionResponse[]` | — |

`ProductResponse`: `productId, sellerId, name, description, startingPrice, createdAt`.
`AuctionResponse`: `auctionId, productId, sellerId, startingPrice, status, startTime, endTime, winnerId, winningPrice, reassignmentCount, createdAt, updatedAt`. `status ∈ {WAITING, ACTIVE, CLOSED, COMPLETED, FAILED}`. `sellerId`·`startingPrice`는 bid-service가 입찰 검증에 쓰는 값이라 응답에 포함한다.

## bid-service (8082)

| 메서드·경로 | 헤더 | 요청 본문 | 성공 | 오류 |
|---|---|---|---|---|
| `POST /api/v1/bids` | `X-User-Id` | `{ "auctionId": 1, "amount": 15000 }` | 201 `BidResponse` | 400 `INVALID_REQUEST`·`BID_AMOUNT_TOO_LOW`, 403 `SELLER_CANNOT_BID`, 404 `AUCTION_NOT_FOUND`, 409 `AUCTION_NOT_ACTIVE`·`AUCTION_ALREADY_ENDED`, 502, 503 |
| `GET /api/v1/bids?auctionId={id}` | — | — | 200 `BidResponse[]` (amount 내림차순) | 400(파라미터 누락) |
| `GET /api/v1/bids/{bidId}` | — | — | 200 `BidResponse` | 404 `BID_NOT_FOUND` |
| `POST /internal/v1/bids/auctions/{auctionId}/winner` | — | — | 200 `WinnerResponse` | — |

`BidResponse`: `bidId, auctionId, bidderId, amount, status, createdAt`. `status ∈ {ACTIVE, OUTBID, WINNER}`.
`WinnerResponse`: `{ "auctionId": 1, "hasBids": true, "winningBid": BidResponse }` 또는 `{ "auctionId": 3, "hasBids": false, "winningBid": null }`. 입찰 없음은 항상 200 + `hasBids=false`로 표현한다. 이 API는 멱등이다 — 이미 WINNER가 있으면 같은 Bid를 돌려준다.

bid-service가 auction-service를 호출할 때의 오류 전달: 상류 404 → 404 `AUCTION_NOT_FOUND`; 연결 실패·타임아웃·5xx → 503; 그 외 4xx 또는 `sellerId`/`startingPrice` 누락 → 502.

## payment-service (8083)

| 메서드·경로 | 헤더 | 요청 본문 | 성공 | 오류 |
|---|---|---|---|---|
| `POST /internal/v1/payments` | — | `{ "auctionId": 1, "payerId": 3, "amount": 12000, "idempotencyKey": "1-3-0" }` (전부 필수, amount > 0, key ≤ 64자) | 201 신규 `PaymentResponse` / 200 같은 키의 기존 건 | 400 |
| `GET /api/v1/payments/{paymentId}` | `X-User-Id` | — | 200 `PaymentResponse` | 403 `FORBIDDEN`(payerId ≠ X-User-Id), 404 `PAYMENT_NOT_FOUND` |

`PaymentResponse`: `paymentId, auctionId, payerId, amount, idempotencyKey, status, failureReason, createdAt, updatedAt`. `status ∈ {REQUESTED, COMPLETED, FAILED}`; `failureReason`은 FAILED일 때 `"SIMULATED_FAILURE"`, 그 외 null.

`idempotencyKey`가 identity다: 같은 키로 다시 요청하면 본문(금액 등)이 달라도 기존 Payment를 200으로 돌려준다. 기존 건이 `REQUESTED`로 남아 있으면 확정한 뒤 돌려준다. 호출자가 응답으로 `REQUESTED`를 받으면 아직 확정되지 않은 것이므로 재요청해야 한다.

## 이벤트 인터페이스 (Kafka)

D8에서 구현 예정. 이벤트 페이로드 DTO는 common 모듈에 정의 완료.

| 이벤트 | 토픽 (예정) | 파티션 키 | 발행자 | 소비자 |
|--------|------------|-----------|--------|--------|
| AuctionStartedEvent | auction-events | auctionId | auction-service | bid-service |
| BidPlacedEvent | bid-events | auctionId | bid-service | (CQRS용) |
| AuctionClosedEvent | auction-events | auctionId | auction-service | bid-service |
| AuctionWonEvent | auction-events | auctionId | auction-service | payment-service |
| PaymentCompletedEvent | payment-events | auctionId | payment-service | auction-service |
| PaymentFailedEvent | payment-events | auctionId | payment-service | auction-service |
| WinnerReassignedEvent | auction-events | auctionId | auction-service | payment-service |

모든 이벤트에 eventId(UUID)와 occurredAt(LocalDateTime) 공통 필드 포함. D9에서 AuctionWonEvent가 위의 `POST /internal/v1/payments` 동기 호출을 대체한다.
