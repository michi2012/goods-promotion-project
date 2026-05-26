# 맥락 노트: Codex MCP 교차 코드 리뷰어 추가

## 왜 이 방식을 선택했는가

### 병행 구조 선택 이유
기존 `code-reviewer.md`는 Claude(작성 모델과 동일)가 리뷰한다. "교차(cross)" 리뷰의 진짜 가치는 **다른 모델의 시각**에 있다. Codex(OpenAI)는 다른 회사, 다른 학습 데이터, 다른 편향을 가지므로 Claude가 놓친 패턴을 잡을 가능성이 높다. 두 리뷰어를 대체가 아닌 병행으로 운영하는 이유는 각자 역할이 다르기 때문이다:
- `code-reviewer` (Claude): 프로젝트 컨벤션, Spring Boot 패턴, 수술적 변경 원칙 — 이 코드베이스를 아는 리뷰어
- `codex-reviewer` (Codex): 범용 버그/보안/설계 — 프로젝트 지식 없는 외부 시각

### 수동 트리거 선택 이유
자동(Stop hook) 트리거는 매 코드 작성 완료마다 Codex API를 호출 → OpenAI API 비용이 선형 증가. 교차 리뷰는 "중요한 변경이나 확신이 필요할 때" 추가로 쓰는 것이 현실적이다.

### OPENAI_API_KEY를 .mcp.json에 보관하는 이유
Claude Code의 MCP 서버 `env` 섹션은 literal 값만 지원하며 다른 파일에서 변수 interpolation을 지원하지 않는다. 시스템 환경 변수로 설정하면 `.mcp.json`에 키 없이 상속되지만, 사용자가 Windows 시스템 환경 변수를 설정하지 않은 상황이므로 `.mcp.json` 직접 기록이 가장 단순한 경로다. 이미 mysql 자격증명도 `.mcp.json`에 존재한다.

## 검토했으나 채택하지 않은 대안

### 대안 A: Codex를 기존 code-reviewer 대체
- 무엇: 기존 code-reviewer.md를 제거하고 Codex 단독 운영
- 왜 안 썼나: 기존 리뷰어가 이 프로젝트의 Spring Boot 컨벤션, 수술적 변경 원칙 등 프로젝트 특화 검사를 수행한다. Codex는 이 맥락을 모른다. 제거하면 프로젝트 지식 기반 리뷰가 사라진다.

### 대안 B: Stop hook 자동 교차 리뷰
- 무엇: 코드 작성 완료(Stop) 시마다 자동으로 Codex 리뷰 실행
- 왜 안 썼나: 매 작업마다 OpenAI API 호출 → 비용 증가. 간단한 수정에도 리뷰가 붙어 워크플로우 방해. 수동 호출이 비용과 편의 양쪽에서 우월하다.

### 대안 C: 시스템 환경 변수 OPENAI_API_KEY 설정
- 무엇: Windows 시스템 환경 변수로 키 설정, .mcp.json에는 키 없이 프로세스 상속으로 해결
- 왜 안 썼나: 사용자가 현재 API Key가 없는 상태이며, 시스템 환경 변수 설정은 Claude Code 외부 작업. .mcp.json 직접 기입이 더 단순하고 검증하기 쉽다.

### 대안 D: mcp-server-openai 3rd party 패키지
- 무엇: `@modelcontextprotocol/server-openai` 같은 서드파티 MCP 서버로 GPT-4o 직접 호출
- 왜 안 썼나: Codex CLI는 전문 코딩 에이전트이며 raw API 호출보다 코드 분석에 특화된 컨텍스트와 기능을 제공한다. 공식 OpenAI 패키지를 쓰는 것이 유지보수 면에서 안전하다.

## 관련 파일/위치
- `.mcp.json` — MCP 서버 정의. codex 항목 추가 대상
- `.claude/settings.json` — enabledMcpServers에 "codex" 추가 대상
- `.claude/agents/code-reviewer.md` — 기존 Claude 기반 리뷰어. 변경 없음
- `.claude/agents/codex-reviewer.md` — 신규 Codex 기반 교차 리뷰어 에이전트
