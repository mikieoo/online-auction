# D16 — Terraform으로 GKE 인프라 프로비저닝

## 왜 필요한가

K8s 클러스터를 콘솔 클릭으로 만들면:
- 재현 불가 — 지웠다 다시 만들 때 세팅 기억 필요
- 리뷰 불가 — 인프라 변경 사항을 코드 리뷰할 수 없음
- 환경 차이 — dev/prod 일관성 유지 불가

Terraform은 인프라를 `.tf` 파일로 선언하고, `terraform apply`로 생성/변경/삭제한다.

## Terraform 핵심 개념

| 개념 | 설명 |
|---|---|
| Provider | 어떤 클라우드를 쓸지 (google) |
| Resource | 생성할 인프라 (VPC, GKE, Artifact Registry) |
| State | 현재 인프라 상태 추적 파일 |
| Plan | 변경 미리보기 (apply 전 필수 확인) |
| Apply | 실제 인프라 변경 |
| Destroy | 전부 삭제 |

## 워크플로우

```
tf 파일 작성 → terraform init   (프로바이더 다운로드)
             → terraform validate (문법 검사, GCP 인증 불필요)
             → terraform plan     (변경 미리보기, GCP 인증 필요)
             → terraform apply    (실제 적용)
             → terraform destroy  (전부 삭제, 비용 정리)
```

## 파일 구조

```
infra/terraform/
├── main.tf                ← provider 설정, 로컬 값 (공통 라벨)
├── variables.tf           ← 입력 변수 (project_id, region, cluster_name)
├── terraform.tfvars.example ← 변수 값 예시
├── vpc.tf                 ← VPC + Subnet (Pod/Service 보조 IP 대역)
├── gke.tf                 ← GKE Autopilot 클러스터
├── artifact-registry.tf   ← Docker 이미지 저장소
├── outputs.tf             ← 클러스터 이름, 엔드포인트, 레지스트리 URL
└── .gitignore             ← .terraform/, *.tfstate* 제외
```

## 각 리소스 설명

### VPC + Subnet

```
google_compute_network "auction_vpc"
└─ auto_create_subnetworks = false (수동 제어)

google_compute_subnetwork "auction_subnet"
├─ 10.0.0.0/20  — 노드용 (4,096 IPs)
├─ 10.1.0.0/16  — Pod용 보조 대역 (65,536 IPs)
└─ 10.2.0.0/20  — Service용 보조 대역 (4,096 IPs)
```

GKE VPC-native 클러스터는 Pod/Service에 별도 IP 대역이 필요하다.
Pod가 노드 IP를 공유하면 네트워크 정책·로드밸런싱이 복잡해지므로,
각각 고유 IP를 부여하는 것이 GKE 표준.

### GKE Autopilot

```
google_container_cluster "auction"
├─ enable_autopilot = true   — 노드 자동 관리
├─ location = asia-northeast3 (서울)
├─ deletion_protection = false (dev)
└─ ip_allocation_policy → Pod/Service 보조 대역 연결
```

Autopilot 선택 이유:
- Pod가 없으면 비용이 거의 없음 (Pod requests 기준 과금)
- 노드 관리 불필요 — K8s 입문에 적합
- 보안 기본 적용 (shielded nodes, workload identity)

### Artifact Registry

```
google_artifact_registry_repository "auction"
├─ format = DOCKER
├─ cleanup_policies: 30일 이상 태그 없는 이미지 자동 삭제
└─ 이미지 URL: {region}-docker.pkg.dev/{project}/auction-platform/{service}:{tag}
```

CI에서 Docker 이미지를 push할 대상. GCR(Container Registry)의 후속 서비스.

## Outputs

| 출력 | 용도 |
|---|---|
| cluster_name | Helm 배포 시 대상 클러스터 |
| cluster_endpoint | K8s API 서버 주소 (sensitive) |
| registry_url | docker push 대상 URL 접두사 |
| get_credentials_command | kubectl 인증 명령어 복붙용 |

## State 관리

- **로컬 state** — 1인 개발이므로 `terraform.tfstate` 파일로 저장
- `.gitignore`에 추가 — state 파일에 민감 정보 포함 가능
- 팀 협업 시 GCS bucket backend로 전환 (락 + 공유)

## 비용 제어

| 리소스 | 과금 | 제어 |
|---|---|---|
| GKE Autopilot | Pod requests 기준 + 관리 수수료 | 작업 후 `terraform destroy` |
| Artifact Registry | 저장 용량 (GB당 $0.10/월) | cleanup policy |
| VPC/Subnet | 무료 | — |

**핵심: 작업 끝나면 `terraform destroy`로 전부 삭제 → 비용 0**

## 면접 포인트

1. **왜 IaC인가** — 재현성, 버전 관리, 코드 리뷰. 콘솔 클릭은 기록 없음
2. **terraform plan** — apply 전 항상 실행. 실수로 리소스 삭제 방지
3. **State를 git에 넣으면 안 되는 이유** — 민감 정보 포함 가능, 원격 backend 사용
4. **VPC-native 클러스터** — Pod/Service에 고유 IP → 네트워크 정책·LB 단순화
5. **Autopilot 과금** — requests 기준, 워크로드 0이면 관리 수수료만
6. **deletion_protection** — prod에서는 true, dev에서는 false (terraform destroy 허용)
