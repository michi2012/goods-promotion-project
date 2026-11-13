# E2E 적용 값 — promotion 프로젝트

범용 원칙은 같은 폴더의 `SKILL.md`. 이 파일은 이 프로젝트에만 해당하는 값이다.

## 시나리오와 스크립트 위치 (작업 B에서 생성 예정)

| 층 | 위치 | 대표 시나리오 |
|------|------|------|
| API E2E | `frontend/e2e/api/` (Playwright `request`) | 상품 생성 → 구매 → 주문·결제가 금액과 함께 PAID → 주문 상태 조회도 PAID |
| UI E2E | `frontend/e2e/` | 구매 화면에서 구매 → 주문 상태 페이지에 PAID 표시 |
| 사전 점검 | `frontend/e2e/preflight.ts` (Playwright `globalSetup`) | 아래 점검 대상 |

## 사전 점검 대상
- 컨테이너: `mysql`, `mysql-c`, `redis`, `redis-b`, `kafka`, `kafka-connect`, `discovery-service`, `server-a`, `server-b`, `server-c`
- Debezium 커넥터 2개(`promotion-outbox-connector`, `payment-outbox-connector`)와 각 태스크가 RUNNING
- serverA·B·C의 health 엔드포인트 응답

## 실행 시점

| 시점 | 실행 범위 |
|------|------|
| 로컬 (커밋 전) | 바꾼 부분의 테스트. 흐름을 건드렸으면 API E2E, 화면을 건드렸으면 UI E2E까지 |
| CI – PR마다 | 백엔드 단위·통합·계약 테스트 + 프론트 lint·Vitest·build (UI E2E는 mock 기준) |
| CI – main 머지 후 / 야간 / 수동 | 전체 스택 E2E (docker compose + API·UI E2E) |
| 배포 후 | 스모크. 저장소 변수 `DEPLOY_ENABLED=true`로 배포가 실제로 돌았을 때만 실행 |

## 로컬 검증이 불가능할 때

| 상황 | 커밋 전 검증 기준 |
|------|-----------------|
| 로컬에서 바로 실행 가능 | 로컬 E2E 후 커밋 |
| 기본 환경은 없지만 우회 수단이 있음 (kind, ngrok, docker compose 등) | 우회 수단을 설치·구성해서라도 검증 후 커밋. "귀찮다"는 이유로 생략 금지 |
| 진짜 로컬 불가 (AWS ALB, EKS RBAC 등 클라우드 전용) | `gradle build` + `helm template` 통과 후 커밋. EKS 배포 후 사후 검증 |
| 값 조정, 문서 변경 | 육안 확인으로 충분 |

- **로컬 kubectl 통과 = RBAC 통과가 아니다.** 로컬 kubeconfig는 cluster-admin이므로 ServiceAccount 권한 제약이 전혀 검증되지 않는다. rbac.yaml에서 누락된 권한은 EKS에서만 발견된다.
