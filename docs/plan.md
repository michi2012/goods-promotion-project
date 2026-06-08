# 계획서: v1.7.0 변경 내용을 README.md / docs/port.md에 반영

- 작성일: 2026-06-08
- 관련 이슈/티켓: 없음 (문서 정비 작업)

## 목표
v1.7.0에서 새로 추가된 결제 종단 간 p95 지연 모니터링/알림(`PaymentE2ELatencyHigh`, 대시보드 패널)과 Linear MCP 워크플로우 통합(`pr`, `spec-draft`, `spec-to-tickets` 등)을 README.md와 docs/port.md의 관련 섹션에 반영한다.

## 성공 기준
- [ ] README.md "6. 모니터링 — SRE 대시보드 & 알람 체계"의 Tier 표·알람 표에 신규 e2e p95 패널/알림이 반영됨 (육안 확인)
- [ ] README.md에 AI 개발 워크플로우 섹션이 신설되어 슬래시 명령어·Linear MCP 연동이 간략히 소개됨 (육안 확인)
- [ ] docs/port.md 모니터링 섹션 알람 표에 `PaymentE2ELatencyHigh` 행이 추가됨 (육안 확인)
- [ ] docs/port.md AI 개발 워크플로우 섹션의 슬래시 명령어 표에 `pr`, `spec-draft`, `spec-to-tickets`가 추가되고 Linear MCP 연동이 서술에 반영됨 (육안 확인)
- [ ] 기존 표/문단 구조를 유지한 최소 diff로 작성됨 (`git diff` 확인)

## 비범위 (Out of Scope)
- README.md AI 워크플로우 섹션은 port.md 수준의 상세 서술(문제/대안/성과 narrative)을 복제하지 않고, 핵심 명령어·연동 구조를 간략히 소개 후 port.md로 링크하는 수준으로 한정
- docs/design-notes.md, docs/arch-snapshot.md 수정 (이번 변경과 무관함을 확인함)
- docs/CHANGELOG.md 수정 (`/release-notes`가 자동 생성)

## 단계별 작업 계획

### 단계 1: README.md 모니터링 섹션 업데이트
- 변경 파일: README.md
- 변경 내용 요약: "6. 모니터링 — SRE 대시보드 & 알람 체계"의 Tier 2 대시보드 표에 결제 종단 간 p95 패널 언급 추가, 알람 규칙 표 P2 행(또는 신규 행)에 `PaymentE2ELatencyHigh` (결제 종단 간 p95 > 2초) 추가
- 검증 방법: 육안 확인 + 표 마크다운 렌더링 깨짐 없는지 확인
- 롤백 방법: `git checkout -- README.md`
- 예상 소요: 짧음

### 단계 1-1: README.md에 AI 개발 워크플로우 섹션 신설
- 변경 파일: README.md
- 변경 내용 요약: "핵심 설계 결정"과 "성능 개선 히스토리" 사이에 새 `## AI 개발 워크플로우` 섹션 추가 — CLAUDE.md 4원칙, 도메인 Skills, `plan`/`pr`/`spec-draft`/`spec-to-tickets`/`incident`/`release-notes` 등 슬래시 명령어, Linear MCP 연동을 간략한 표·문단으로 소개하고 상세 내용은 [docs/port.md](docs/port.md) 링크로 위임
- 검증 방법: 육안 확인 + 마크다운 렌더링 확인
- 롤백 방법: `git checkout -- README.md`
- 예상 소요: 보통

### 단계 2: docs/port.md 모니터링 섹션 업데이트
- 변경 파일: docs/port.md
- 변경 내용 요약: "[전 계층 통합 모니터링 환경]" 섹션의 Prometheus 알람 표(245-249행)에 `PaymentE2ELatencyHigh` 행 추가, Tier 2 대시보드 표에 종단 간 p95 패널 언급 보강
- 검증 방법: 육안 확인
- 롤백 방법: `git checkout -- docs/port.md`
- 예상 소요: 짧음

### 단계 3: docs/port.md AI 워크플로우 섹션 업데이트
- 변경 파일: docs/port.md
- 변경 내용 요약: 자동화 워크플로우 스킬 표(354-364행)에 `pr`, `spec-draft`, `spec-to-tickets` 행 추가, Linear MCP 연동(plan/incident/release-notes에서 이슈 조회·코멘트 작성 자동화) 관련 서술 1~2문장 보강
- 검증 방법: 육안 확인
- 롤백 방법: `git checkout -- docs/port.md`
- 예상 소요: 짧음

## 리스크 및 대응
- 리스크: 마크다운 표 컬럼 정렬이 깨질 수 있음 → 기존 행과 동일한 컬럼 수·구분자(`|`) 형식을 그대로 따름
- 리스크: port.md와 README의 동일 알람 표 서술이 어긋날 수 있음 → 동일한 임계치(2초)·메트릭명을 양쪽에 일관되게 기입

## 의존성
없음 (순수 문서 변경)
