# 맥락 노트: 테스트 실행 정책 변경 + skill 문서 교정

## 왜 이 방식을 선택했는가
PR #15(Next.js 프론트 재작성) 머지 후, 외부 코드리뷰로 skill 문서 몇 군데의 오류(구식 Vite 기준, 번호 참조, MSW 경로 불일치, client.ts 하드코딩)가 드러났다. 동시에 사용자가 "테스트 실행을 왜 전부 사용자에게 위임하냐, 현업 에이전트는 로컬에서 직접 돌리지 않냐"는 질문을 던졌고, 대화 중 다음 결론에 도달했다:
- CI가 있으면 "로컬에서 누가 돌리냐"는 최종 안전성에 영향을 주지 않는다(어차피 CI가 재검증). 그래서 백엔드처럼 CI가 있는 영역은 Claude가 로컬에서 직접 돌려도 안전.
- CI가 없는 영역(현재 프론트)은 로컬 실행이 유일한 방어선이라, 오히려 Claude가 더 적극적으로 직접 돌려야 한다.
- 다만 "무겁거나 환경 의존적인 테스트"까지 Claude가 매번 인프라를 직접 구성해서 돌리는 건 비용이 크므로, "환경이 이미 떠 있을 때만 돌린다"는 선으로 제한했다. (인프라를 구성해서까지 검증하는 행위는 CLAUDE.md의 별도 규칙인 "커밋 전 로컬 E2E 검증"에서만 적용하고, 이번 "테스트 스위트 실행" 규칙과는 분리했다.)
- "일단 시도해보고 느리면 넘긴다"는 단일 규칙 대신 사전 분류(빠른 테스트 vs 무거운 테스트)를 유지하기로 했다 — 느린 걸 느리다고 판단하려면 이미 한 번 돌려봐야 하는 역설이 있어서, 사전에 분류 기준(외부 인프라 필요 여부)으로 거르는 쪽이 더 효율적이라고 판단했다.

## 검토했으나 채택하지 않은 대안
### 대안 A: 모든 테스트를 Claude가 인프라까지 구성해서 직접 실행
- 무엇: Testcontainers/Docker/Playwright 브라우저까지 Claude가 매번 띄워서 검증
- 왜 안 썼나: 테스트 스위트 실행이라는 반복적인 행위치고는 비용(시간/토큰)이 너무 큼. "로컬 E2E 검증"(기능이 실제로 동작하는지 1회성으로 확인)은 이미 별도 규칙으로 존재하고 그건 그대로 유지.

### 대안 B: "일단 시도, 느리면 넘긴다"는 단일 규칙으로 단순화
- 무엇: 테스트 종류를 사전에 분류하지 않고, Claude가 먼저 실행해보고 오래 걸리면 중단하고 사용자에게 넘김
- 왜 안 썼나: 사전 분류 없이 시도하면 "느리다"는 판단 자체를 위해 이미 시간을 써버리는 역설이 생김. 어노테이션/인프라 요구사항으로 사전에 거르는 게 더 효율적.

## 기존 코드베이스 컨벤션
- CLAUDE.md 테스트 분업 원칙(변경 전): "작성/수정은 Claude, `gradlew test`/`npm run test` 실행은 사용자" — 이번에 변경 대상
- `.claude/skills/testing/SKILL.md`: 백엔드 테스트 작성 규칙(슬라이스/단위/통합 테스트 패턴, Testcontainers Singleton Container 패턴) — 이번엔 자가점검 한 줄만 수정
- `frontend/src/lib/api/client.ts`: `NEXT_PUBLIC_API_MODE` 환경변수로 Prism/실제 백엔드를 전환하는 기존 패턴이 있음 — 이번에 추가하는 `NEXT_PUBLIC_API_BASE_URL`도 같은 `process.env.NEXT_PUBLIC_*` 관례를 따름

## 관련 파일/위치
- `CLAUDE.md` — 루트 지침, 테스트 분업 원칙 섹션
- `.claude/skills/testing/SKILL.md` — 백엔드 테스트 skill
- `.claude/skills/frontend-testing/SKILL.md` — 프론트 테스트 skill
- `.claude/skills/frontend-design-feedback/SKILL.md` — 디자인 피드백 루프 skill (이번에 처음 교정)
- `.claude/skills/frontend/SKILL.md` — 프론트 구현 skill
- `frontend/src/lib/api/client.ts` — 실제 openapi-fetch 래퍼

## 외부 참조
없음
