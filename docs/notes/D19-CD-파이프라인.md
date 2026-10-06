# D19 — CD 파이프라인 (GitHub Actions → GKE)

## 왜 필요한가

D14에서 CI(빌드+테스트+이미지 빌드)까지 만들었지만, 이미지를 레지스트리에 푸시하거나 클러스터에 배포하지는 않았다. main에 머지할 때마다 수동 배포하는 건 실수의 원인이고 반복 비용이다.

## 파이프라인 구조

```
PR → CI (build-and-test + docker-build 검증)
main push → CI (build-and-test) → CD (docker-push → deploy)
```

### CI (`ci.yml`)
- `build-and-test`: Gradle 빌드 + 테스트 (모든 이벤트)
- `docker-build`: 이미지 빌드 검증 (PR만, push 안 함)

### CD (`cd.yml`)
- `docker-push`: 5개 서비스 이미지를 Artifact Registry에 push (matrix 병렬)
- `deploy`: Helm으로 GKE 배포

## 핵심 설계

### Workload Identity Federation

```yaml
- uses: google-github-actions/auth@v2
  with:
    workload_identity_provider: ${{ secrets.GCP_WIF_PROVIDER }}
    service_account: ${{ secrets.GCP_SERVICE_ACCOUNT }}
```

서비스 계정 키 JSON을 시크릿에 넣는 방식은 키 유출 위험이 있다. WIF는 GitHub의 OIDC 토큰으로 GCP에 인증하므로 **키 자체가 존재하지 않는다**.

### 이미지 태그 = git SHA

```
{REGION}-docker.pkg.dev/{PROJECT}/{REPO}/{SERVICE}:{GIT_SHA}
```

- `latest`는 어떤 코드인지 추적 불가. 롤백도 불가.
- git SHA는 불변이고 커밋과 1:1 매핑.

### Helm 배포

```bash
helm upgrade --install auction-platform ./infra/helm/auction-platform \
  --set global.image.registry=${REGISTRY} \
  --set global.image.tag=${GITHUB_SHA} \
  --wait --timeout 5m
```

- `--install`: 첫 배포 시 자동 설치 (멱등)
- `--wait`: 모든 Pod Ready까지 대기
- `--timeout 5m`: Java 서비스 기동이 느릴 수 있으므로 여유

### Helm values.yaml 수정

```yaml
global:
  image:
    registry: ""    # CD에서 --set으로 주입. 로컬은 빈 문자열 → 로컬 이미지 사용
    tag: latest     # CD에서 git SHA로 오버라이드
```

Deployment 템플릿이 registry가 있으면 `{registry}/{repo}:{tag}`, 없으면 `{repo}:{tag}`로 렌더링.

## 필요한 GitHub Secrets

| Secret | 예시 | 용도 |
|---|---|---|
| `GCP_PROJECT_ID` | `my-auction-project` | GCP 프로젝트 |
| `GCP_REGION` | `asia-northeast3` | 리전 |
| `GCP_WIF_PROVIDER` | `projects/123/locations/global/workloadIdentityPools/...` | WIF provider |
| `GCP_SERVICE_ACCOUNT` | `github-actions@project.iam.gserviceaccount.com` | 서비스 계정 |
| `GKE_CLUSTER_NAME` | `auction-cluster` | 클러스터 이름 |

## 면접 포인트

1. **CI vs CD** — CI는 코드 품질 게이트(빌드+테스트), CD는 검증된 코드의 자동 배포
2. **Workload Identity Federation** — 키 없는 인증. OIDC 토큰 교환 방식
3. **이미지 태그 전략** — `latest` 지양, git SHA로 불변 태그. 롤백 시 이전 SHA로 재배포
4. **helm upgrade --install** — 멱등 배포. 실패 시 `helm rollback`으로 이전 릴리스 복원
5. **배포 전략** — Rolling Update(기본, 점진적), Blue/Green(즉시 전환), Canary(일부만 먼저)
