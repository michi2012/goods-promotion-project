# 계획서: Codex MCP 교차 코드 리뷰어 추가

- 작성일: 2026-05-26

## 목표
OpenAI Codex CLI를 MCP 서버로 등록하여, 기존 Claude 기반 code-reviewer와 별개로 Codex(OpenAI 모델)가 교차 리뷰를 수행하는 구조를 구축한다. 두 리뷰어를 병행 운영하며, 수동 트리거 방식으로 호출한다.

## 성공 기준
- [ ] Claude Code 재시작 후 `/mcp` 명령어에 codex 서버가 "connected" 상태로 표시됨
- [ ] `codex-reviewer` 에이전트 호출 시 Codex MCP 도구를 통해 리뷰 보고서가 출력됨
- [ ] 기존 `code-reviewer` 에이전트 동작에 영향 없음 (기존대로 동작)
- [ ] OPENAI_API_KEY가 설정되어 Codex 서버가 인증 오류 없이 기동됨

## 비범위 (Out of Scope)
- Stop hook 기반 자동 트리거
- Codex로 기존 code-reviewer 대체 또는 병합
- 두 리뷰어 결과 자동 비교/합산 기능
- Codex의 코드 생성/수정 기능 활용

## 단계별 작업 계획

### 단계 1: .mcp.json에 codex 서버 항목 추가
- 변경 파일: `.mcp.json`
- 변경 내용: `@openai/codex` 패키지를 npx로 실행하는 codex 서버 항목 추가. `mcp` 서브커맨드로 MCP 서버 모드 기동. OPENAI_API_KEY는 값을 이 단계에서 플레이스홀더("REPLACE_ME")로 기록
- 검증 방법: JSON 문법 유효 확인
- 롤백 방법: `git checkout .mcp.json`
- 예상 소요: 짧음

### 단계 2: OPENAI_API_KEY 주입 및 codex 활성화
- 변경 파일: `.mcp.json` (API Key 값 대입), `.claude/settings.json`
- 변경 내용: `.mcp.json`의 OPENAI_API_KEY 플레이스홀더를 실제 키 값으로 교체 (사용자가 직접 입력). `.claude/settings.json`의 `enabledMcpServers`에 "codex" 추가
- 검증 방법: Claude Code 재시작 → `/mcp` 명령어로 codex 서버 연결 상태 확인
- 롤백 방법: enabledMcpServers에서 "codex" 제거, .mcp.json에서 codex 항목 제거
- 예상 소요: 짧음

### 단계 3: codex-reviewer 에이전트 정의 파일 생성
- 변경 파일: `.claude/agents/codex-reviewer.md` (신규)
- 변경 내용: Codex MCP 도구(`mcp__codex__codex`)를 사용하는 교차 리뷰어 에이전트 정의. 리뷰 포커스: 버그, 보안 취약점, 성능, Spring Boot 안티패턴. 입력: git diff 또는 지정 파일 내용. 출력: 마크다운 리뷰 보고서
- 검증 방법: "Codex로 교차 리뷰해줘" 입력 시 codex-reviewer 에이전트 활성화 및 보고서 출력 확인
- 롤백 방법: 파일 삭제
- 예상 소요: 보통

## 리스크 및 대응
- 리스크: `codex mcp` 서브커맨드가 설치된 버전에서 미지원 → 대응: 단계 2 검증 시 실패하면 `@openai/codex@latest` 버전 확인 및 `--mcp` 플래그 대안 시도
- 리스크: OPENAI_API_KEY가 `.mcp.json`에 평문 저장되어 git에 노출 → 대응: `.mcp.json`을 `.gitignore`에 추가 여부 사용자에게 확인 (이미 mysql 자격증명도 포함된 파일)
- 리스크: Codex MCP가 노출하는 도구명이 `mcp__codex__codex`가 아닐 수 있음 → 대응: 단계 2에서 연결 후 `/mcp` 명령어로 실제 도구 목록 확인 후 에이전트 파일 조정

## 의존성
- Node.js / npx: 이미 mysql MCP에서 사용 중 — 설치 확인됨
- OPENAI_API_KEY: 사용자가 발급 후 `.mcp.json`에 직접 입력
- `@openai/codex` npm 패키지: npx 실행 시 자동 다운로드
