# 계획서: Linear MCP 기반 개발 워크플로우 자동화 (명령어 5종 통합)

- 작성일: 2026-06-08
- 관련 이슈/티켓: (없음 — 이번 작업의 산출물로 향후 티켓 생성 가능)

## 목표
Claude Code 슬래시 커맨드에 Linear MCP를 연동해, "명세서 작성 → 티켓 생성 → 개발 착수 → 인시던트 후속조치 → 릴리즈 동기화"로 이어지는 트래커 통합 파이프라인을 구축한다.

## 성공 기준
- [ ] `/spec-draft`에 부실한 요구사항 메모를 입력하면 User Story + Given-When-Then 구조의 정식 명세서 `.md`가 생성된다
- [ ] `/spec-to-tickets`에 정식 명세서를 입력하면 실제 Linear MIC 팀에 이슈가 생성된다 (Title/Description/AC/assignee=본인)
- [ ] `/plan` 실행 시 Linear 이슈 ID를 입력하면 description/AC가 plan.md 초안에 반영되고, 승인 시 이슈 상태가 "In Progress"로 전환된다
- [ ] `/incident`에 aiops 초안 텍스트를 붙여넣으면 Step 1이 이를 인식해 빠진 항목만 질문하고, RCA 완료 후 "재발 방지" 항목이 Linear 서브태스크로 생성된다
- [ ] `/release-notes`가 커밋 메시지에서 `MIC-숫자` 형식 이슈 ID를 추출해 "포함된 이슈" 섹션을 만들고, 발행 시 해당 이슈 상태를 "Done"으로 전환한다

## 비범위 (Out of Scope)
- aiops 백엔드의 자동 RCA 초안 생성·Slack 연동(Phase 1·2 자동화) — 코드베이스와 작업 성격이 달라 별도 plan으로 분리
- Linear 워크스페이스의 워크플로우 상태(status) 커스터마이징 — 기존 기본 상태(Todo/In Progress/Done 등) 그대로 사용
- 다인 팀 라우팅/할당 로직 — 현재 솔로 워크스페이스이므로 항상 본인에게 할당

## 단계별 작업 계획

### 단계 1: `/spec-draft` 신규 명령어 작성
- 변경 파일: `.claude/commands/spec-draft.md` (신규)
- 변경 내용 요약: 부실한 요구사항 초안 `.md`를 입력받아, User Story(As a/I want/So that) + Given-When-Then 수용 기준 형식의 정식 명세서로 재작성하는 가이드를 작성한다. 결과물은 새 `.md` 파일로 저장 제안 후 사용자 승인을 받는다.
- 검증 방법: 임의의 짧은 요구사항 메모(2~3줄)를 입력해 `/spec-draft` 실행 → 결과물이 User Story + GWT 구조를 갖추는지 육안 확인
- 롤백 방법: 신규 파일이므로 파일 삭제
- 예상 소요: 보통

### 단계 2: `/spec-to-tickets` 신규 명령어 작성
- 변경 파일: `.claude/commands/spec-to-tickets.md` (신규)
- 변경 내용 요약: 정식 명세서 `.md`를 입력받아 User Story 단위로 파싱하고, 각 Story를 Linear 이슈(Title/Description/AC/assignee=me/team=MIC)로 변환해 미리보기 출력 → 사용자 승인 후 Linear MCP로 실제 생성, 결과 URL 목록을 출력한다.
- 검증 방법: 단계 1의 산출물(또는 샘플 명세서)을 입력해 `/spec-to-tickets` 실행 → Linear MCP로 MIC 팀을 재조회해 이슈가 실제 생성되었는지 확인
- 롤백 방법: 신규 파일 삭제 + 테스트 중 생성된 이슈는 Linear에서 아카이브
- 예상 소요: 보통

### 단계 3: `/plan`에 Linear 연동 추가
- 변경 파일: `.claude/commands/plan.md`
- 변경 내용 요약: Step 1에 "Linear 이슈 ID 입력 시 이슈의 description/AC를 조회해 plan.md 목표·성공기준 초안에 반영" 절차를 추가한다. Step 5(승인) 통과 직후 해당 이슈 상태를 "In Progress"로 전환하고 plan.md 경로를 코멘트로 첨부하는 절차를 추가한다.
- 검증 방법: MIC 팀에 테스트 이슈를 하나 만들고 그 ID로 `/plan` 실행 → plan.md에 이슈 내용이 반영되는지, 승인 후 상태가 전환되는지 확인
- 롤백 방법: 추가한 섹션을 `git checkout -- .claude/commands/plan.md`로 되돌림
- 예상 소요: 보통

### 단계 4: `/incident`에 aiops 초안 입력 처리 + Linear 서브태스크 생성 추가
- 변경 파일: `.claude/commands/incident.md`
- 변경 내용 요약: Step 1 안내에 "aiops 초안 텍스트를 붙여넣으면 기존 '이미 제공된 정보는 건너뛰는' 규칙을 그대로 적용해 빠진 항목만 질문" 문구를 추가한다. Step 4(파일 저장) 뒤에 신규 Step 5(Linear 연동)를 추가해, "재발 방지" 표의 각 행을 Linear 서브태스크로 생성할지 묻고 승인 시 생성한다.
- 검증 방법: 가상의 장애 시나리오로 `/incident` 실행 → "재발 방지" 항목이 실제 Linear 서브태스크로 생성되는지 확인
- 롤백 방법: 추가 섹션 git checkout + 테스트 중 생성된 서브태스크 Linear에서 아카이브
- 예상 소요: 보통

### 단계 5: `/release-notes`에 이슈 ID 추출 + 상태 전환 추가
- 변경 파일: `.claude/commands/release-notes.md`
- 변경 내용 요약: Step 1의 PowerShell 분류 로직에 커밋 메시지에서 `MIC-\d+` 패턴을 추출하는 로직을 추가하고 `ISSUE_IDS` 출력을 더한다. Step 3 포맷에 "포함된 이슈" 섹션을 추가한다. Step 4(발행) 뒤에 추출된 이슈들을 일괄 "Done" 상태로 전환하는 절차를 추가한다.
- 검증 방법: 커밋 메시지에 `MIC-1` 같은 ID를 포함한 테스트 커밋으로 `/release-notes` 실행 → 이슈 ID 추출 및 섹션 생성 확인 (실제 상태 전환은 테스트 이슈로 검증)
- 롤백 방법: 추가한 PowerShell 로직과 섹션을 git checkout
- 예상 소요: 짧음~보통

### 단계 6: 전체 파이프라인 E2E 통합 검증
- 변경 파일: 없음 (검증 전용)
- 변경 내용 요약: `/spec-draft` → `/spec-to-tickets` → `/plan` → (가상 인시던트) `/incident` → `/release-notes` 순으로 실제 실행해 데이터가 자연스럽게 이어지는지 확인하고, 테스트 중 생성된 모든 Linear 이슈·서브태스크를 정리(아카이브)한다.
- 검증 방법: 각 단계 출력 캡처 + Linear MCP로 최종 워크스페이스 상태 재조회해 더미 데이터 잔존 여부 확인
- 롤백 방법: 해당 없음 (검증 전용 단계)
- 예상 소요: 보통

## 리스크 및 대응
- 리스크 1: Linear MCP 도구(`save_issue`, `get_issue`, `save_comment` 등)의 정확한 입력 스키마를 사전에 모름 → 대응: 각 단계 구현 직전 `ToolSearch`로 스키마를 로드해 정확한 필드명·필수값을 확인 후 작성
- 리스크 2: 테스트 중 실제 Linear 워크스페이스에 더미 이슈·서브태스크가 누적됨 → 대응: 각 단계 검증 직후 즉시 아카이브, 단계 6에서 최종 정리 재확인
- 리스크 3: 5개 명령어를 한 plan에서 다루어 단계가 김 → 대응: 절대 한 번에 한 단계만 실행, 매 단계 검증 통과 후에만 다음으로 진행

## 의존성
- Linear MCP 연결 (완료 — `Michi2012` 팀, key `MIC` 확인됨)
- `mcp__linear__save_issue`, `get_issue`, `save_comment`, `list_issue_statuses` 등 도구 — 구현 단계 직전에 `ToolSearch`로 스키마 로드 필요
