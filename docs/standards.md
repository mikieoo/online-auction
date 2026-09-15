# 코드 규칙과 검증 게이트

## 검증 게이트

커밋 전 `./gradlew clean build` 통과 필수. exit 0이 아니면 커밋하지 않는다.

## 모듈 경계

- common 모듈에는 이벤트 DTO만 포함. 엔티티, 리포지토리, 서비스 로직은 서비스 모듈에만 존재.
- 서비스 모듈은 다른 서비스 모듈을 직접 의존하지 않는다. common만 의존.
- Gateway는 Spring Cloud Gateway(Reactive/Netty) 기반. 서블릿 의존성을 추가하지 않는다.

## 패키지 구조

`com.auction.{서비스명}.{계층}` — 계층: controller, service, domain, repository, event.

## 버전 고정

| 항목 | 버전 |
|------|------|
| Java | 17 (sourceCompatibility) |
| Spring Boot | 3.3.5 |
| Spring Cloud | 2023.0.4 |
| MySQL | 8.0 |
| Gradle | 8.10 (wrapper) |

## 네이밍

- 이벤트 DTO: `{동작}Event` (예: AuctionStartedEvent). 공통 필드: eventId(String), occurredAt(LocalDateTime).
- 테이블명: snake_case 단수형. PK: `{테이블명}_id`.
- REST 엔드포인트: `/api/v1/{리소스명}` (복수형).

## DB

- JPA ddl-auto: none. 스키마는 SQL 파일로 관리.
- ACTIVE Auction 유일성은 애플리케이션 레벨에서 검증 (MySQL은 partial unique index 미지원).

## 테스트

핵심 비즈니스 로직(입찰 검증, 동시성 제어, Saga 흐름) 우선 커버. JUnit 5 + Testcontainers.
