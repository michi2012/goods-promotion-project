# 백로그

로드맵·후속 작업·범위 밖 발견 사항의 단일 목록. 관리 규칙은 `CLAUDE.md` "백로그 관리".
- 마지막 업데이트: 2026-11-13
- 분류: 로드맵 / 후속 작업 / 버그·정합성 / 기술 부채 / 검증 대기 / 정리
- 우선순위: 높음 / 중간 / 낮음

## 진행 중
| ID | 항목 | 문서 |
|----|------|------|
| BL-001 | **작업 B**: Playwright API E2E(구매 → 결제 금액·PAID, 주문 상태 PAID) + 사전 점검 + 구매 UI E2E PAID 확인 + 프론트 CI(lint·Vitest·build) + 백엔드 CI 미사용 환경변수 정리 + PR CI 분리·병렬·Gradle 캐시 | `docs/plan.md` (`feature/e2e-api-and-ci`) |
| BL-011 | `frontend/src/app/orders/[orderId]/page.tsx` lint 3건 — 프론트 CI lint 통과를 위해 BL-001에 포함 | `docs/plan.md` (`feature/e2e-api-and-ci`) |

## 진행 예정 (우선순위순)
| ID | 항목 | 분류 | 우선순위 | 출처 |
|----|------|------|---------|------|
| BL-002 | **결제 로드맵 2단계: Spring Batch 정산** — Job/Step/chunk, 키셋 페이징, 정산일 JobParameters 멱등, 재시작, Skip/Retry, 수수료 정책(시점별 요율)·BigDecimal 반올림·컷오프·원장, 별도 모듈 + K8s CronJob. Spring Batch는 새 의존성(승인 필요) | 로드맵 | 높음 | 결제 로드맵 (2026-11-12) |
| BL-003 | Saga 결과 이벤트가 영영 오지 않는 경우(CDC 유실) serverC PAID인데 주문 PENDING/EXPIRED로 남음 → serverC 측 재대사 등 장치 필요 | 버그·정합성 | 높음 | PR #22 검증 |
| BL-004 | 금액 필수화 (확장-축소 마지막 단계): 운영에서 `business_payment_legacy_without_amount_total`이 계속 0인지 확인 후, 금액 없는 `payment-request` 거부 | 후속 작업 | 중간 | PR #22 |
| BL-005 | 중복 망취소(금액 불일치 망취소, 늦은 성공 보상 중복 수신) 시 실제 PG 응답 처리 미검증 (모의 PG는 멱등) | 버그·정합성 | 중간 | PR #22 |
| BL-006 | 결제조회 UNKNOWN이 길어질 때(serverC 장애) 주문 PENDING·재고 묶임 → 최대 대기 시간·알람 (`saga.timeout.payment_unknown` 기반) | 버그·정합성 | 중간 | PR #22 |
| BL-007 | 전체 스택 E2E CI 워크플로 (main 머지 후 / 야간 / 수동, docker compose 기반) — 백엔드 E2E(`backend-e2e/`) + UI E2E(`frontend/e2e/`)를 함께 실행. 러너 메모리·시간 실측 후 결정 | 후속 작업 | 중간 | E2E 회고 |
| BL-019 | GitHub 브랜치 보호의 "필수 체크"를 도입할 때: 경로 필터로 `backend-ci`·`frontend-ci`가 실행되지 않은 PR은 필수 체크가 대기 상태로 남아 머지가 막힘 → skip 처리 방식(항상 실행되는 요약 job 등) 함께 결정 | 후속 작업 | 낮음 | BL-001 작업 중 발견 |
| BL-020 | 수동 확인·팀 간 공유용 API 도구 (Postman 컬렉션 또는 IntelliJ `.http`). 자동 검증 원본은 `backend-e2e/` — 같은 검증을 중복 자동화하지 않음 | 후속 작업 | 낮음 | BL-001 작업 중 논의 |
| BL-021 | 백엔드 E2E를 REST Assured(Gradle 모듈)로 전환 — **조건부**: 전환 조건은 `docs/design-notes.md` "E2E 두 층과 도구" | 후속 작업 | 낮음 | BL-001 작업 중 논의 |
| BL-022 | 프론트만 구현된 상황의 UI E2E — Prism mock 기준 (지금은 Vitest+MSW 컴포넌트 테스트로 대체) | 후속 작업 | 낮음 | BL-001 작업 중 논의 |
| BL-008 | **결제 로드맵 3단계**: 대용량 배치 vs 실시간 분리 — 시드 데이터로 OFFSET vs 키셋 실측, 배치 전용 커넥션 풀. 읽기 복제본은 설계·답변 위주 | 로드맵 | 중간 | 결제 로드맵 |
| BL-009 | 정산 2차: 부분 취소, PG 정산 내역 대사 | 로드맵 | 낮음 | 결제 로드맵 |
| BL-010 | `values-local.yaml`(로컬 kind) serverA 데이터소스 DB 이름이 `promotion` — 실제는 `order` | 기술 부채 | 낮음 | PR #22 검증 |
| BL-012 | Helm 각 Deployment의 `EUREKA_DEFAULT_ZONE` 환경변수가 K8s에서 미사용 | 기술 부채 | 낮음 | PR #21 |
| BL-013 | `/plan` skill 문서의 Windows 전용 지시(PowerShell 빈 파일 생성, 백슬래시 경로) — 팀 운영 시 정리 | 기술 부채 | 낮음 | BL-000 작업 중 발견 |
| BL-014 | 로컬 DB 테스트 데이터 정리 (상품 id 19·21, userId 9001~9005 주문) | 정리 | 낮음 | PR #22 검증 |

## 검증 대기 (EKS 재가동 후)
| ID | 항목 | 출처 |
|----|------|------|
| BL-015 | 저장소 변수 `DEPLOY_ENABLED=true` 설정 (미설정이면 배포 파이프라인 skipped) | PR #23 |
| BL-016 | Debezium serverA 커넥터 감시 DB `order` 변경 반영 — 기존 커넥터 오프셋·schema history 초기화 필요 여부 확인 | PR #22 |
| BL-017 | K8s DiscoveryClient 정적 등록의 실제 Service DNS 연결: serverA 결제조회, cs-bot 조회, aiops → codebot | PR #21 |
| BL-018 | 배포 후 스모크 테스트 job 추가 (`deploy` 뒤, 백엔드 E2E `backend-e2e/` 재사용 — 대상 주소만 변경) | E2E 회고 |

## 완료
| 항목 | PR |
|------|----|
| K8s DiscoveryClient 서비스 조회 복구 (serverA·cs-bot·aiops) + arch-snapshot 최신화 | #21 |
| 결제 금액 기본기 + Saga 안전망(UNKNOWN 재시도·늦은 결제 성공 보상) + Debezium DB 이름 + serverA 계약 테스트 | #22 |
| main CI 정상화 (serverB 계약 테스트 + `DEPLOY_ENABLED`) | #23 |
| BL-000 작업 규칙 정비 (범용 e2e skill + 단일 백로그 + 개인 환경 규칙 분리) | #24 |
