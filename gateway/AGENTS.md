# gateway

## 범위

HTTP 라우팅, JWT 토큰 검증, X-User-Id 헤더 전달.

## 범위 밖

- 비즈니스 로직 일체 → 각 서비스
- DB 없음

## 불변 조건

- Spring Cloud Gateway(Reactive/Netty) 기반. spring-boot-starter-web(서블릿)을 추가하면 기동 실패.
- JWT 검증 실패 시 401 응답. 내부 서비스로 요청을 전달하지 않는다.

## 구현 상태

D1에서 모듈 + 진입점만 생성. 라우팅 설정과 JWT 필터는 D4에서 구현 예정.
