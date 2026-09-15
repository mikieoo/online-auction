# 시스템 구성

## 서비스 토폴로지

```
클라이언트 → Gateway(8080) ──REST──→ auction-service(8081) ──REST/gRPC──→ bid-service(8082)
                                       │                                      │
                                       │←──── Kafka ──── payment-service(8083)│
                                       │                        │              │
                                       └──── Kafka ─────────────┘──── Kafka ──┘
```

- **Gateway** → 모든 서비스: HTTP 라우팅, JWT 검증 후 X-User-Id 헤더 전달.
- **auction-service** → **bid-service**: 동기 통신 (REST, D5부터 gRPC). 경매 상태·시작가 조회, 최고 입찰자 목록 조회.
- **auction-service** → **bid-service**: Kafka 비동기. AuctionStarted, AuctionClosed 이벤트.
- **auction-service** → **payment-service**: Kafka 비동기. AuctionWon, WinnerReassigned 이벤트.
- **payment-service** → **auction-service**: Kafka 비동기. PaymentCompleted, PaymentFailed 이벤트.

## 데이터 소유권

| 서비스 | DB | 소유 테이블 |
|--------|-----|-----------|
| auction-service | auction_db | product, auction, outbox |
| bid-service | bid_db | bid |
| payment-service | payment_db | payment |
| gateway | 없음 | — |

각 서비스는 자신의 DB만 접근한다. 다른 서비스의 데이터가 필요하면 동기 API 호출 또는 이벤트를 통해 얻는다.

## 대표 흐름: 경매 마감 → 결제

1. 스케줄러(auction-service)가 end_time 도달한 경매를 CLOSED로 변경
2. auction-service가 bid-service에 최고 입찰자 목록을 동기 조회
3. 입찰 0건이면 FAILED(유찰). 입찰 있으면 최고 입찰자를 낙찰자로 설정
4. AuctionWon 이벤트를 Kafka로 발행 (idempotencyKey 포함)
5. payment-service가 AuctionWon을 소비하여 Payment 생성 → 결제 시뮬레이션
6. 성공 시 PaymentCompleted, 실패 시 PaymentFailed 발행
7. auction-service가 결제 결과를 소비하여 COMPLETED 또는 차순위 승계(최대 3회)

## Kafka 파티션 키

모든 경매 관련 이벤트의 파티션 키는 auctionId. 같은 경매의 이벤트 순서를 보장한다.
