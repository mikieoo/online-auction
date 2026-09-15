# 외부 인터페이스 계약

REST API는 D2~D3에서 구현 예정. 현재 정의된 인터페이스 없음.

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

모든 이벤트에 eventId(UUID)와 occurredAt(LocalDateTime) 공통 필드 포함.
