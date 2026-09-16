# 온라인 경매 시스템

1인 개발 30일 MSA 학습 + 포트폴리오 프로젝트. 서비스 3개(auction / bid / payment) + Gateway, Gradle 멀티 모듈. GCP 프리티어/$300 크레딧 범위 내 운영.

## 문서 구조

```
├── CLAUDE.md                          ← 프로젝트 진입점 (이 파일)
├── AGENTS.md                          ← Codex 진입점 (CLAUDE.md와 동일)
├── docs/
│   ├── architecture.md                ← 시스템 구성과 서비스 간 통신
│   ├── business-rules.md              ← 도메인 규칙과 상태 전이
│   ├── security.md                    ← 인증·인가 정책
│   ├── standards.md                   ← 코드 규칙과 검증 게이트
│   ├── engineering-notes.md           ← 트랩과 비자명 메커니즘
│   ├── operations.md                  ← 셋업·빌드·배포 절차
│   ├── contracts.md                   ← 외부 인터페이스 계약
│   └── tracking/
│       ├── status.md                  ← 현재 진행 상황
│       ├── decisions/
│       │   ├── index.md               ← 결정 기록 색인
│       │   ├── 0001-db-per-service.md ← 서비스별 DB 분리
│       │   └── 0002-d3-sync-settlement.md ← D3 동기 정산·임시 상태 인코딩
│       └── findings.md               ← 미해결 문제
├── auction-service/AGENTS.md          ← auction-service 모듈 범위
├── bid-service/AGENTS.md              ← bid-service 모듈 범위
├── payment-service/AGENTS.md          ← payment-service 모듈 범위
├── gateway/AGENTS.md                  ← gateway 모듈 범위
└── common/AGENTS.md                   ← common 모듈 범위
```

## 핵심 규칙

- 서비스 간 엔티티·리포지토리 공유 금지. common 모듈에는 이벤트 DTO만 포함.
- `./gradlew clean build` 통과 없이 커밋하지 않는다.
- 동시성·Saga·Outbox·멱등성·Kafka 컨슈머·Terraform·K8s는 "왜 그렇게 했나"에 답할 수준까지 구현.
- 작업 종료 시 GKE 워크로드를 0으로 내릴 것.

## 작업 전 확인

- `docs/standards.md` — 코드 규칙과 검증 게이트
- `docs/engineering-notes.md` — 알려진 트랩
- 해당 모듈의 `AGENTS.md` — 모듈 범위와 경계
- 입찰 로직 수정 전: `docs/business-rules.md`의 입찰 규칙 + 동시성 직렬화 절
- 스케줄러·서비스 간 호출 수정 전: `docs/engineering-notes.md`의 트랜잭션 경계·Feign·ShedLock 항목, `docs/contracts.md`의 내부 API 계약
- 상태 전이 변경 전: `docs/business-rules.md`의 상태 전이 다이어그램
- DB 스키마 변경 전: `docs/engineering-notes.md`의 스키마 관련 항목

## 문제 발생 시

- 데이터 격리 위반 (서비스 A가 서비스 B의 DB를 직접 조회) → 즉시 보고
- 동시성 버그 (락 없이 공유 상태 변경) → 즉시 보고
- 그 외 → `docs/tracking/findings.md`에 기록

## 사용자 배경

Kafka, GitHub Actions CI 경험 있음. Terraform, K8s는 처음. 답변은 한국어로, 솔직하고 직접적으로.
