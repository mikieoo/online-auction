# D15 — Kubernetes 배포 기초 (Helm Chart)

## 왜 필요한가

Docker Compose는 단일 머신 전용. 프로덕션에서는:
- 여러 노드에 걸쳐 컨테이너 분산 배치
- 자동 복구 (Pod 죽으면 재시작), 스케일링, 롤링 업데이트
- K8s가 이 모든 것을 선언적으로 관리

## K8s 핵심 오브젝트

| 오브젝트 | 역할 |
|---|---|
| Deployment | Pod 몇 개, 어떤 이미지, 어떤 설정으로 띄울지 선언 |
| Service | Pod에 안정적인 DNS 주소 부여 (ClusterIP, LoadBalancer 등) |
| ConfigMap | 환경 변수·설정 파일 주입 |
| Secret | 민감 정보 (DB 비밀번호, JWT 시크릿) |

## 왜 Helm인가

5개 서비스가 거의 동일한 구조 → 직접 yaml 작성하면 중복 과다.
Helm은 K8s manifest를 **템플릿화**해서 재사용하는 패키지 매니저.

```
직접 yaml: 5 × (deployment + service + configmap) = 15개 파일, 거의 복붙
Helm: 공통 템플릿 1벌 + values.yaml에 서비스별 값 → 5개 서비스 자동 생성
```

## Chart 구조

```
infra/helm/auction-platform/
├── Chart.yaml          ← 차트 이름, 버전
├── values.yaml         ← 기본값 (서비스 목록, 인프라 호스트, 시크릿)
├── templates/
│   ├── _helpers.tpl    ← 공통 라벨·이름 헬퍼
│   ├── deployment.yaml ← range로 서비스 순회, Deployment 생성
│   ├── service.yaml    ← range로 서비스 순회, Service 생성
│   ├── configmap.yaml  ← 서비스별 환경 변수 (DB URL, Kafka, Eureka 등)
│   └── secret.yaml     ← DB 비밀번호, JWT 시크릿 (base64)
```

## 설정 주입 방식

application-k8s.yml 같은 프로필 파일 대신 **환경 변수로 오버라이드**:

```yaml
# ConfigMap
SPRING_DATASOURCE_URL: jdbc:mysql://auction-mysql:3306/auction_db?...
SPRING_KAFKA_BOOTSTRAP_SERVERS: auction-kafka:29092
EUREKA_CLIENT_SERVICE_URL_DEFAULTZONE: http://discovery-service:8761/eureka

# Secret
SPRING_DATASOURCE_PASSWORD → secretKeyRef
JWT_SECRET → secretKeyRef
```

Spring Boot는 환경 변수를 자동으로 application.yml 값에 매핑한다.
(`SPRING_DATASOURCE_URL` → `spring.datasource.url`)

## 템플릿 핵심 패턴: range

```yaml
{{- range $name, $svc := .Values.services }}
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ $name }}
spec:
  replicas: {{ $svc.replicaCount }}
  ...
{{- end }}
```

values.yaml에 서비스를 추가하면 Deployment·Service·ConfigMap이 자동 생성된다.

## Probe 설정

```yaml
readinessProbe:          # "트래픽 받을 준비 됐나?"
  httpGet: /actuator/health
  initialDelaySeconds: 40  # Spring Boot 기동 대기
  periodSeconds: 10

livenessProbe:           # "아직 살아있나?"
  httpGet: /actuator/health
  initialDelaySeconds: 70  # readiness보다 여유 있게
  periodSeconds: 15
```

- readiness 실패 → Service에서 제외 (트래픽 안 감)
- liveness 실패 → Pod 재시작

## 서비스 네트워크 구성

| 서비스 | Service 타입 | 이유 |
|---|---|---|
| gateway | LoadBalancer | 외부 트래픽 진입점 |
| auction, bid, payment | ClusterIP | 내부 통신만 (Eureka 경유) |
| discovery-service | ClusterIP | 내부 통신만 |

bid-service는 HTTP(8082) + gRPC(9082) 두 포트를 Service에 등록.

## Resource 설정

```yaml
resources:
  requests:
    cpu: 200m       # 스케줄링 기준
    memory: 384Mi
  limits:
    memory: 512Mi   # 초과 시 OOMKill
```

CPU limit은 의도적으로 안 걸었다 — GKE Autopilot에서는 requests 기반으로 과금하고,
CPU limit은 throttling을 유발해서 오히려 성능이 나빠질 수 있다.

## 렌더링된 오브젝트 (16개)

| 종류 | 개수 | 내용 |
|---|---|---|
| Secret | 1 | mysql-password, jwt-secret |
| ConfigMap | 5 | 서비스별 환경 변수 |
| Service | 5 | gateway(LB) + 4개(ClusterIP) |
| Deployment | 5 | readiness/liveness probe 포함 |

## 검증

```bash
helm lint infra/helm/auction-platform/           # 문법 검사
helm template auction-platform infra/helm/...    # 렌더링 확인
```

실제 클러스터 배포는 D16(Terraform + GKE)에서 진행.

## 면접 포인트

1. **Deployment vs Pod** — Pod 직접 생성은 죽으면 끝. Deployment가 desired state 유지
2. **readiness vs liveness** — readiness=트래픽 제어, liveness=재시작 판단. 둘 다 같은 엔드포인트도 OK
3. **ConfigMap vs Secret** — Secret은 base64 인코딩 + etcd 암호화 가능. 코드에 비밀번호 안 넣는 것이 핵심
4. **Helm range** — 동일 구조 서비스를 반복 생성, values만 다르게 → DRY 원칙
5. **CPU limit 안 거는 이유** — throttling 방지. GKE Autopilot은 requests 기반 과금
