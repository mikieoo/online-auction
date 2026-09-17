# discovery-service

## 범위

로컬 개발용 Eureka 서버(8761). gateway·auction·bid·payment가 서비스 이름으로 서로의 주소를 찾게 한다.

## 범위 밖

- 비즈니스 로직, DB, 인증 — 없음
- GKE 배포 대상이 아니다. K8s에서는 Service DNS가 이 역할을 대신한다(D18). 이 모듈에 운영용 설정(이중화, 보안)을 쌓지 않는다.

## 불변 조건

- 자기 자신을 등록하지도, 레지스트리를 가져오지도 않는다(`register-with-eureka: false`, `fetch-registry: false`). 단일 인스턴스.
- self-preservation을 끄고 eviction 주기(5초)·응답 캐시 갱신(5초)을 줄여 둔 것은 **로컬 전용** 선택이다. 서비스를 수시로 껐다 켜는 환경에서 죽은 인스턴스가 레지스트리에 남아 죽은 주소로 라우팅되는 시간을 줄이기 위해서다. 운영 환경이라면 self-preservation은 켜 두는 것이 기본이다.
- 클라이언트 쪽(각 서비스 `application.yml`)의 lease 갱신 5초 / 만료 15초 / 레지스트리 조회 5초와 짝을 이룬다. 한쪽만 바꾸면 전파 지연이 다시 길어진다.
- docker-compose에 넣지 않는다. Gradle 모듈로 `bootRun`/jar 실행.

## 구현 패턴

`DiscoveryServiceApplication`(`@EnableEurekaServer`) + `application.yml`이 전부다. 의존성은 `spring-cloud-starter-netflix-eureka-server` 하나(서블릿 웹을 포함한다).

## 테스트 가이드

자동 테스트 없음(설정만 있는 모듈). 확인은 기동 후 `http://localhost:8761` 대시보드 또는 `GET /eureka/apps`(Accept: application/json)에 네 애플리케이션이 UP으로 보이는지로 한다.
