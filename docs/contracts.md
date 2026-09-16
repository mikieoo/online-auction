# 외부 인터페이스 계약

## 공통 규약 (REST)

- 경로: 공개 API는 `/api/v1/{복수 리소스}`, 서비스 간 내부 API는 `/internal/v1/...`. Gateway(D4)는 `/api/**`만 라우팅하고 `/internal/**`는 외부에 노출하지 않는다.
- 인증: 공개 API 중 쓰기·본인 확인이 필요한 요청은 `X-User-Id: {userId}` 헤더가 필수(없으면 400). D3까지는 클라이언트가 직접 넣고, D4부터 Gateway가 JWT를 검증해 넣는다. 내부 API는 헤더 없음(서비스 간 무인증).
- 본문: JSON. 금액은 number(소수 2자리, 원), 시각은 ISO-8601 `LocalDateTime`(예: `2026-09-16T23:16:38`), 식별자는 정수.
- 성공 상태: 생성 201, 조회·변경 200. 멱등 생성 API는 기존 건 반환 시 200.
- 오류 본문(세 서비스 동일):

```json
{ "code": "AUCTION_NOT_ACTIVE", "message": "진행 중인 경매에만 입찰할 수 있습니다. auctionId=7, 현재 상태: WAITING" }
```

| HTTP | 의미 | code |
|---|---|---|
| 400 | 값 검증 실패(필수 누락, 형식, 음수), 헤더 누락, 본문 파싱 실패 | `INVALID_REQUEST` |
| 400 | 입찰 금액 규칙 위반 | `BID_AMOUNT_TOO_LOW` |
| 403 | 판매자 본인 경매 입찰 | `SELLER_CANNOT_BID` |
| 403 | 타인 상품·경매·결제에 대한 요청 | `FORBIDDEN` |
| 404 | 리소스 없음 | `PRODUCT_NOT_FOUND`, `AUCTION_NOT_FOUND`, `BID_NOT_FOUND`, `PAYMENT_NOT_FOUND` |
| 404 | 매핑되지 않은 경로 | `NOT_FOUND` |
| 409 | 상태 위반 | `AUCTION_NOT_ACTIVE`, `AUCTION_ALREADY_ENDED`, `ACTIVE_AUCTION_EXISTS`, `INVALID_STATE_TRANSITION` |
| 502 | 상류 서비스가 예상 밖 4xx 또는 계약 위반 응답 | `UPSTREAM_ERROR` |
| 503 | 상류 서비스 연결 실패·타임아웃·5xx | `UPSTREAM_UNAVAILABLE` |
| 500 | 그 외 | `INTERNAL_ERROR` |

`message`는 한국어 설명이며 프로그램 분기에 쓰지 않는다. 분기는 `code`로 한다.

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
