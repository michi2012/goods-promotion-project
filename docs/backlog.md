# 백로그

로드맵·후속 작업·범위 밖 발견 사항의 단일 목록. 관리 규칙은 `CLAUDE.md` "백로그 관리".
- 마지막 업데이트: 2026-11-14
- **다음 ID: BL-028** (항목 추가 시 이 번호를 쓰고 1 올린다)
- 분류: 로드맵 / 후속 작업 / 버그·정합성 / 기술 부채 / 검증 대기 / 정리
- 우선순위: 높음 / 중간 / 낮음
- 규모: S(파일 몇 개, 반나절 이내) / M(PR 하나, 하루~며칠) / L(여러 PR 또는 새 모듈)
- 섹션: 바로 착수 가능한 것은 "진행 예정", 환경 선행 조건이 있는 것은 "EKS 재가동 후", 특정 조건·변경이 생길 때만 필요한 것은 "조건부"
- 선행: 다른 항목이 먼저 끝나야 하는 후속 항목은 선행 항목 바로 아래에 두고 `선행` 칸에 ID를 적는다 (이 경우 우선순위 정렬보다 묶음이 우선)

## 진행 중
| ID | 항목 | 문서 |
|----|------|------|

## 진행 예정 (우선순위순 — 추가 시 우선순위 위치에 삽입, 후속 항목은 선행 항목 아래)
| ID | 항목 | 분류 | 우선순위 | 규모 | 선행 | 출처 |
|----|------|------|---------|------|------|------|
| BL-002 | **결제 로드맵 2단계: Spring Batch 정산** — Job/Step/chunk, 키셋 페이징, 정산일 JobParameters 멱등, 재시작, Skip/Retry, 수수료 정책(시점별 요율)·BigDecimal 반올림·컷오프·원장, 별도 모듈 + K8s CronJob. Spring Batch는 새 의존성(승인 필요) | 로드맵 | 높음 | L | | 결제 로드맵 (2026-11-12) |
| BL-008 | **결제 로드맵 3단계**: 대용량 배치 vs 실시간 분리 — 시드 데이터로 OFFSET vs 키셋 실측, 배치 전용 커넥션 풀. 읽기 복제본은 설계·답변 위주 | 로드맵 | 중간 | L | BL-002 | 결제 로드맵 |
| BL-009 | 정산 2차: 부분 취소, PG 정산 내역 대사 | 로드맵 | 낮음 | L | BL-002 | 결제 로드맵 |
| BL-006 | 결제조회 UNKNOWN이 길어질 때(serverC 장애) 주문 PENDING·재고 묶임 → 최대 대기 시간·알람 (`saga.timeout.payment_unknown` 기반) | 버그·정합성 | 중간 | M | | PR #22 |
| BL-025 | 명령 문서의 OS 의존: `/db-migration`·`/coverage`·`/release-notes`가 PowerShell 스크립트로 집계·필터링, `/codex-review`에 "Windows 절대경로" — 팀 운영 시 Mac/Linux에서 동작 안 함. 프로젝트 런타임(Node/Gradle) 또는 OS 중립 지시로 전환 | 기술 부채 | 낮음 | M | | BL-013 작업 중 발견 |
| BL-026 | 루트 k6 스크립트(`load-test.js`·`load-test2.js`)를 `perf/k6/`로 이동 — `perf-compare` skill의 스크립트 위치에 맞춤. 참조(`docs/frontend-flow.md`, skill `project.md`) 함께 갱신 | 정리 | 낮음 | S | | perf-compare skill 작업 중 발견 |

## EKS 재가동 후
| ID | 항목 | 규모 | 선행 | 출처 |
|----|------|------|------|------|
| BL-015 | 저장소 변수 `DEPLOY_ENABLED=true` 설정 (미설정이면 배포 파이프라인 skipped) | S | | PR #23 |
| BL-018 | 배포 후 스모크 테스트 job 추가 (`deploy` 뒤, 백엔드 E2E `backend-e2e/` 재사용 — 대상 주소만 변경) | M | BL-015 | E2E 회고 |
| BL-016 | Debezium serverA 커넥터 감시 DB `order` 변경 반영 — 기존 커넥터 오프셋·schema history 초기화 필요 여부 확인 | S | | PR #22 |
| BL-017 | K8s DiscoveryClient 정적 등록의 실제 Service DNS 연결: serverA 결제조회, cs-bot 조회, aiops → codebot | S | | PR #21 |
| BL-023 | `ci-cd.yaml`에 Git Flow 흔적: `branches: [dev, main]`, dev 환경 분기, `create-pr`(dev 배포 후 main PR 생성) job — 브랜치 전략은 GitHub Flow(main + 기능 브랜치). 배포 환경(staging/prod) 설계와 함께 정리 | M | | BL-019 작업 중 발견 |

## 조건부 (조건 충족 시 "진행 예정"으로 이동 — 선행 조건이 있거나 특정 변경이 생길 때만 필요한 항목)
| ID | 항목 | 조건 | 규모 | 출처 |
|----|------|------|------|------|
| BL-004 | 금액 필수화 (확장-축소 마지막 단계): 금액 없는 `payment-request` 거부 | 운영에서 `business_payment_legacy_without_amount_total`이 계속 0 | S | PR #22 |
| BL-021 | 백엔드 E2E를 REST Assured(Gradle 모듈)로 전환 | `docs/design-notes.md` "E2E 두 층과 도구"의 전환 조건 | M | BL-001 작업 중 논의 |
| BL-007 | 전체 스택 E2E CI 워크플로 (docker compose 기반) — 백엔드 E2E(`backend-e2e/`) + UI E2E(`frontend/e2e/`). 논의된 설계: 트리거는 main 머지 후(경로 필터)·수동·워크플로 파일 변경 PR, 야간 없음 / 필요한 서비스 11개만 기동(grafana 3000 포트 충돌) / 한 job에서 순차 실행 / 표준 러너(PUBLIC, 4 vCPU·16GB) 실측 후 기록 / `bitnami/kafka:latest` pull 실패 가능성. 현업 표준은 스테이징 E2E(BL-018)이며, 이 항목은 스테이징이 없는 동안의 대체재 또는 배포 전 게이트 | ① EKS 재가동 후 BL-018과 함께 역할(대체·배포 전 게이트·통합) 판단, 또는 ② 로컬 E2E 누락으로 main의 서버 간 흐름이 깨진 사례 발생 | M | E2E 회고, 조건부 이동 #38 |
| BL-020 | 수동 확인·팀 간 공유용 API 도구 (Postman 컬렉션 또는 IntelliJ `.http`). 자동 검증 원본은 `backend-e2e/` — 같은 검증을 중복 자동화하지 않음. 할 때는 `contracts/openapi.yaml`·`contracts/server-a-openapi.yaml`을 가져와(import) 생성 | 팀원 합류, 또는 수동 API 호출이 반복돼 불편할 때 | S | BL-001 작업 중 논의, 조건부 이동 #39 |
| BL-022 | 프론트만 구현된 상황의 UI E2E — Prism mock 기준. 지금은 MSW가 커버(API 호출이 모두 Client Component), 구매 폴링(PENDING→PAID)은 Prism(stateless)으로 표현 불가, serverA·B 계약 2개라 Prism 2개 필요 | Server Component에서 데이터를 가져오는 화면을 백엔드보다 먼저 개발할 때 | M | BL-001 작업 중 논의, 조건부 이동 #37 |
| BL-005 | 중복 망취소(금액 불일치 망취소, 늦은 성공 보상 중복 수신) 시 실제 PG 응답 처리 미검증 (모의 PG는 멱등) | 실제 PG 연동 시 | M | PR #22 |

## 완료
| 항목 | PR |
|------|----|
| K8s DiscoveryClient 서비스 조회 복구 (serverA·cs-bot·aiops) + arch-snapshot 최신화 | #21 |
| 결제 금액 기본기 + Saga 안전망(UNKNOWN 재시도·늦은 결제 성공 보상) + Debezium DB 이름 + serverA 계약 테스트 | #22 |
| main CI 정상화 (serverB 계약 테스트 + `DEPLOY_ENABLED`) | #23 |
| BL-000 작업 규칙 정비 (범용 e2e skill + 단일 백로그 + 개인 환경 규칙 분리) | #24 |
| BL-001 백엔드 E2E(`backend-e2e/`) + UI E2E PAID 확인·사전 점검 + 프론트 CI + PR CI 분리·병렬·Gradle 캐시 | #25 |
| BL-011 orders 페이지 lint 3건 정리 (BL-001에 포함) | #25 |
| BL-019 PR CI 필수 체크 대비 result job + `dev` 트리거 정리 + main 브랜치 보호 적용(필수 체크 `backend-ci-result`·`frontend-ci-result`) | #27 |
| BL-010 `values-local.yaml` serverA DB 이름 `order` + BL-012 Helm 미사용 Eureka 설정 제거(`EUREKA_DEFAULT_ZONE` env 11곳·`eurekaDefaultZone`·`discovery:` 섹션) | #29 |
| BL-013 `/plan` 명령의 Windows 전용 지시 제거 (빈 파일 사전 생성·백슬래시 경로) | #32 |
| BL-003 결과 이벤트 유실 시 종료 주문(EXPIRED/FAILED) 재대사 → serverC PAID면 망취소 + serverA 로컬 `fetch-registry: true` + e2e 로컬 기동 주의사항 | #35 |
| BL-027 CLAUDE.md "재발 방지 기록" 규칙(skill `project.md`/`CLAUDE.local.md`에 즉시 기록) + 자가 점검 4번 항목 | #36 |
| BL-024 codebot `application-k8s.yml` 추가(`eureka.client.enabled: false`) — DiscoveryClient 미사용이라 정적 등록·테스트 불필요. EKS 배포 후 Eureka 등록 실패 로그 사라짐 확인 필요 | #40 |
| BL-014 로컬 테스트 데이터 정리 — **불필요로 종료**: 로컬 DB·Redis는 익명 볼륨이라 `docker compose down` 시 초기화, E2E는 자기 데이터를 직접 만들어 영향 없음 | #32 (기록만) |
