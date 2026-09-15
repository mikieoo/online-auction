# common

## 범위

서비스 간 Kafka 이벤트의 페이로드 DTO 클래스만 포함.

## 범위 밖

- 엔티티, 리포지토리, 서비스 로직은 이 모듈에 넣지 않는다.
- 유틸리티 클래스, 공통 설정도 포함하지 않는다.

## 불변 조건

- 이벤트 DTO만 포함하여 서비스 간 결합도를 최소화한다.
- 모든 이벤트 DTO에 eventId(String)와 occurredAt(LocalDateTime) 공통 필드 포함.

## 현재 이벤트 목록

AuctionStartedEvent, BidPlacedEvent, AuctionClosedEvent, AuctionWonEvent, PaymentCompletedEvent, PaymentFailedEvent, WinnerReassignedEvent.
