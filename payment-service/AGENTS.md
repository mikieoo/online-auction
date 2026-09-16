# payment-service

## 범위

결제(Payment) 생성·처리(시뮬레이션), 결제 조회, 결제 결과 이벤트 발행(D9부터).

## 범위 밖

- 경매·낙찰 관리 → auction-service
- 입찰 → bid-service

## 불변 조건

- idempotencyKey로 중복 결제 방지. 같은 키의 Payment가 이미 존재하면 새로 생성하지 않고 기존 것을 반환한다 — 요청 본문의 금액이 달라도 기존 것이 우선.
- Payment 상태는 REQUESTED → COMPLETED 또는 FAILED. 역전이 없음. REQUESTED로 남은 건은 같은 키로 재요청될 때 재시뮬레이션해 확정한다.
- 시뮬레이션은 결정적: 금액 정수부가 `payment.simulation.failure-suffix`(기본 `9999`)로 끝나면 FAILED(`SIMULATED_FAILURE`), 아니면 COMPLETED. 확률 기반 규칙을 넣지 않는다.
- 결제 조회는 payerId == X-User-Id인 경우만(아니면 403).
- 내부 API(`/internal/v1/payments`)는 인증 없음. 공개 API(`/api/v1/payments/{id}`)는 X-User-Id 필수.

## 구현 패턴

- `PaymentService`(비트랜잭션 파사드) → `PaymentTransactionService`(`@Transactional` 생성·확정·재확정) → `PaymentSimulator`. 파사드가 트랜잭션을 갖지 않는 이유: 같은 키 동시 INSERT로 `DataIntegrityViolationException`이 나면 그 트랜잭션은 rollback-only가 되므로, 기존 건 재조회는 트랜잭션이 끝난 뒤 파사드에서 해야 한다. 이 구조를 합치면 `UnexpectedRollbackException`이 난다.
- INSERT는 `saveAndFlush`로 즉시 실행해 UNIQUE 위반이 호출 시점에 드러나게 한다.
- 패키지: `controller`(InternalPaymentController, PaymentController), `service`, `exception`(GlobalExceptionHandler 포함), `dto`, `domain`, `repository`.

## DB

payment_db — payment 테이블. idempotency_key VARCHAR(64) UNIQUE, failure_reason VARCHAR(255) NULL, status 기본값 REQUESTED. 스키마 원본: `src/main/resources/schema.sql` (사본: `infra/mysql/init/04-payment-schema.sql`, 함께 수정).

## 테스트 가이드

- 멱등성 키 중복 시 기존 Payment 반환(200), 본문이 달라도 기존 반환
- REQUESTED 잔존 건 재요청 → 재시뮬레이션 후 확정
- UNIQUE 위반(동시 요청) → 재조회 후 기존 반환
- 시뮬레이션 접미사 규칙(정수부 기준, 소수부 무시, 설정값 변경)
- 조회 403/404, 신규 201 vs 기존 200
- 결제 결과에 따른 이벤트 발행 (D9)
- 테스트는 Mockito(PaymentRepository mock) + MockMvc standalone. DataSource를 띄우지 않는다.
