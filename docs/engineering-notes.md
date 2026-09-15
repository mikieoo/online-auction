# 트랩과 비자명 메커니즘

## MySQL 8.0 partial unique index 미지원

MySQL은 조건부 유니크 인덱스(partial unique index)를 지원하지 않는다. "하나의 Product에 ACTIVE Auction은 최대 1개"라는 불변 조건은 DB 레벨에서 강제할 수 없으므로, 경매 생성/시작 시 애플리케이션 코드에서 검증해야 한다. 이 검증을 빠뜨리면 동일 상품에 복수 ACTIVE 경매가 생길 수 있다.

## Docker Compose MySQL 초기화 순서

`docker-entrypoint-initdb.d` 디렉토리의 파일은 알파벳 순서로 실행된다. `infra/mysql/init/` 디렉토리에 번호 접두사(01-, 02-, ...)를 붙여 순서를 보장한다. 01에서 DB를 생성하고, 02-04에서 각 DB의 스키마를 적용한다. 순서가 바뀌면 USE 문이 존재하지 않는 DB를 참조하여 실패한다.

## Gateway와 서블릿 의존성 충돌

Spring Cloud Gateway는 Reactive(Netty) 기반이다. spring-boot-starter-web(서블릿)을 Gateway 모듈에 추가하면 WebFlux와 충돌하여 기동 실패한다. Gateway에는 spring-cloud-starter-gateway만 추가하고, web-application-type을 reactive로 설정한다.

## outbox 테이블의 현재 상태

outbox 테이블은 D1에서 스키마만 생성했다. 실제 Outbox 패턴 구현은 D11부터. 스키마를 미리 만들어 둔 이유는 나중에 마이그레이션 없이 바로 사용하기 위해서다.

## 서비스별 schema.sql과 Docker init SQL의 관계

각 서비스의 `src/main/resources/schema.sql`은 해당 서비스의 DDL 원본이다. `infra/mysql/init/` 디렉토리의 SQL 파일은 이 원본에 `USE {db_name};` 문을 추가한 사본이다. 스키마를 수정하면 양쪽을 동기화해야 한다.
