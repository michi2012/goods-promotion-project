# 계획서: 프론트엔드 통합 기반 작업 (React + OpenAPI 계약 자동화)

- 작성일: 2026-06-08
- 관련 이슈/티켓: 없음 (아키텍처 확장 작업)
- 브랜치: feature/frontend-foundation

## 목표
1인 풀스택 운영을 위해 React+TypeScript+Vite 프론트엔드를 이 레포에 도입하고, serverB의 주문 상태 조회 API를 기준으로 "백엔드 OpenAPI 스펙 → 프론트 타입/훅 자동 생성 → 화면 표시 → 테스트"까지 이어지는 파이프라인을 구축·검증한다.

## 성공 기준
- [ ] `frontend/` 디렉토리에 React+TS+Vite 프로젝트가 생성되고 `npm run dev`로 구동된다
- [ ] shadcn/ui 컴포넌트가 최소 1개 이상 적용된 화면이 렌더링된다
- [ ] serverB에 springdoc-openapi가 추가되어 `/v3/api-docs`에서 OpenAPI 스펙이 노출된다 (`gradlew :serverB:bootRun` 후 curl로 확인)
- [ ] orval이 해당 스펙을 읽어 TypeScript 타입과 TanStack Query 훅을 자동 생성한다 (`npx orval` 실행 결과로 확인)
- [ ] "주문 ID를 입력하면 상태를 조회해 화면에 표시"하는 화면이 동작한다 (브라우저에서 실제 호출 확인)
- [ ] Vitest+RTL 컴포넌트 테스트, MSW 기반 API 모킹 테스트, Playwright E2E 테스트가 각 1개 이상 작성되고 통과한다
- [ ] `.claude/skills/frontend`, `.claude/skills/frontend-testing` 매뉴얼이 작성된다

## 비범위 (Out of Scope)
- 실제 비즈니스 화면(상품/프로모션/결제 등) 구현 — 이번엔 검증용 조회 화면 1개만
- 디자인 다듬기 루프(브라우저 MCP 기반 시각 피드백) 적용 — 파이프라인이 갖춰진 후 별도 작업
- Figma MCP 연동
- serverA/C 등 다른 서버에 springdoc-openapi 확장 — 이번엔 serverB만, 패턴 검증 후 후속 작업으로 확장
- 프론트엔드 CI/CD, 배포 파이프라인 구성

## 단계별 작업 계획

### 단계 1: serverB에 springdoc-openapi 추가
- 변경 파일: `serverB/build.gradle`
- 변경 내용 요약: `org.springdoc:springdoc-openapi-starter-webmvc-ui` 의존성 추가. 기존 `@RestController`/DTO를 그대로 introspect하므로 코드 변경은 없음
- 검증 방법: `gradlew :serverB:bootRun` 후 `http://localhost:<port>/v3/api-docs` 호출해 OrderStatusResponse 스펙이 노출되는지 확인
- 롤백 방법: build.gradle의 추가된 의존성 라인 제거
- 예상 소요: 짧음
- ⚠️ 새 Gradle 의존성 추가 — 진행 전 사용자 최종 승인 필요 (CLAUDE.md 절대 금지 사항)

### 단계 2: React+TS+Vite 스캐폴딩 + shadcn/ui 설정
- 변경 파일: `frontend/` 신규 디렉토리 전체 (package.json, vite.config.ts, tailwind.config.js, components.json 등)
- 변경 내용 요약: `npm create vite@latest frontend -- --template react-ts`로 생성, Tailwind CSS 설정, shadcn/ui CLI로 초기화 및 기본 컴포넌트(Button, Input, Card 등) 추가
- 검증 방법: `npm run dev` 구동 후 브라우저에서 shadcn/ui 컴포넌트가 포함된 기본 화면 렌더링 확인
- 롤백 방법: `frontend/` 디렉토리 삭제
- 예상 소요: 보통

### 단계 3: orval + TanStack Query 연동 — 타입/훅 자동 생성 파이프라인
- 변경 파일: `frontend/orval.config.ts`, `frontend/package.json` (스크립트 추가), `frontend/src/api/` (생성 결과물)
- 변경 내용 요약: orval, @tanstack/react-query, axios 설치. `orval.config.ts`에 serverB의 `/v3/api-docs` 출력을 입력으로 지정해 타입+쿼리 훅 생성 스크립트(`npm run generate:api`) 구성
- 검증 방법: `npm run generate:api` 실행 후 `frontend/src/api/`에 `OrderStatusResponse` 타입과 `useGetOrderStatus` 훅이 생성되는지 확인
- 롤백 방법: 추가된 의존성 제거, `orval.config.ts` 및 생성된 `src/api/` 삭제
- 예상 소요: 보통

### 단계 4: 검증 화면 구현 — 주문 상태 조회
- 변경 파일: `frontend/src/pages/OrderStatusPage.tsx` (또는 동등 경로), `frontend/src/App.tsx` (라우팅)
- 변경 내용 요약: React Router로 페이지 등록, shadcn/ui Input+Button+Card로 UI 구성, 단계 3에서 생성된 훅으로 serverB API 호출, 결과를 화면에 표시
- 검증 방법: 브라우저에서 실제 주문 ID 입력 → 상태 조회 결과 표시 확인 (serverB 로컬 구동 필요)
- 롤백 방법: 추가된 페이지/라우팅 제거
- 예상 소요: 보통

### 단계 5: 프론트엔드 테스트 작성 (Vitest+RTL / MSW / Playwright)
- 변경 파일: `frontend/src/pages/OrderStatusPage.test.tsx`, `frontend/src/mocks/handlers.ts` (MSW), `frontend/e2e/order-status.spec.ts` (Playwright), `frontend/vitest.config.ts`, `frontend/playwright.config.ts`
- 변경 내용 요약: 컴포넌트 단위 테스트(RTL), MSW로 serverB 응답을 모킹한 통합 테스트, Playwright E2E 테스트(시각 회귀 스크린샷 1개 + axe-core 접근성 체크 포함) 작성
- 검증 방법: `npm run test` (Vitest), `npx playwright test` 실행 — 사용자가 직접 실행 (CLAUDE.md 테스트 분업 원칙)
- 롤백 방법: 추가된 테스트 파일 및 설정 제거
- 예상 소요: 김

### 단계 6: skill 매뉴얼 작성
- 변경 파일: `.claude/skills/frontend/SKILL.md`, `.claude/skills/frontend-testing/SKILL.md`
- 변경 내용 요약: 기존 `skills/jpa`, `skills/testing` 패턴을 따라 트리거 조건(어떤 키워드/폴더에서 발동)과 컨벤션(컴포넌트 구조, shadcn/ui 사용법, orval 타입 재생성 시점, 테스트 피라미드)을 문서화
- 검증 방법: 기존 SKILL.md들과 형식·트리거 조건 표현 일관성 육안 확인
- 롤백 방법: 추가된 skill 디렉토리 삭제
- 예상 소요: 짧음

## 리스크 및 대응
- 리스크 1: springdoc-openapi가 생성하는 스펙이 기존 DTO 네이밍/구조와 맞지 않아 orval 생성 결과가 어색할 수 있음 → 대응: 단계 1 검증에서 `/v3/api-docs` 출력을 먼저 육안 검토 후 단계 3 진행
- 리스크 2: Gradle(JVM)+npm(Node) 빌드 도구가 한 레포에 공존하며 `.gitignore`/CI 설정 충돌 가능 → 대응: `frontend/.gitignore`를 별도로 두고 루트 빌드(gradlew)와 독립적으로 동작하는지 확인
- 리스크 3: orval 자동 생성 코드가 예상과 다른 형태로 나올 경우 학습 곡선 발생 → 대응: 단계 3에서 생성 결과를 충분히 검토 후 단계 4로 진행, 문제 시 openapi-typescript로 전환 검토 (plan 재협의)

## 의존성
- serverB가 로컬에서 구동 가능해야 함 (`gradlew :serverB:bootRun`)
- Node.js/npm 로컬 설치 필요
- 새 Gradle 의존성(springdoc-openapi) 추가 — 사용자 승인 필요

---

## [진행 중] 디자인 다듬기 루프 (브라우저 MCP 기반 시각 피드백)

- 작성일: 2026-06-08
- 관련 이슈/티켓: 없음
- 브랜치: 확인 필요 (가칭 `feature/design-feedback-loop`)

### 목표
Playwright MCP를 도입해, 프론트엔드 화면 작업 후 Claude가 직접 브라우저에서 렌더링 결과를 스크린샷으로 확인하고, 사용자에게 평범한 말로 시각적 피드백을 받아 코드를 수정→재확인하는 반복 루프를 구축하고 프로젝트 전용 skill로 문서화한다.

### 성공 기준
- [ ] `.mcp.json`에 Playwright MCP 서버가 등록되고 `.claude/settings.json`의 `enabledMcpServers`에 활성화된다
- [ ] Claude가 Playwright MCP 도구로 실행 중인 화면(`localhost:5173`)을 스크린샷/스냅샷으로 확인할 수 있다 (도구 호출 성공으로 확인)
- [ ] 기존 `OrderStatusPage` 화면을 대상으로 "스크린샷 확인 → 설명 → 사용자 피드백 → 코드 수정 → 재확인" 루프 1회 이상을 실제로 수행해 동작을 검증한다
- [ ] `.claude/skills/<skill-name>/SKILL.md`가 작성되어 트리거 조건과 루프 절차를 문서화한다 (기존 `frontend`, `frontend-testing` skill과 형식 일관성 유지)

### 비범위 (Out of Scope)
- Figma MCP 연동 (기존 plan에서도 비범위로 분류 — 디자인 "읽기"는 이번 루프와 별개 작업)
- 시각 회귀 테스트 자동화 강화 (이미 `frontend-testing` skill에 Playwright `toHaveScreenshot` 포함 — 이번 루프는 "사람이 보는 피드백" 워크플로우이지 자동화 테스트가 아님)
- 새로운 화면/컴포넌트 디자인 자체 (이번 작업은 워크플로우 구축이며, 실제 디자인 다듬기는 추후 화면 작업 시 이 루프를 적용)

### 단계별 작업 계획

#### 단계 1: Playwright MCP 서버 등록
- 변경 파일: `.mcp.json`, `.claude/settings.json`
- 변경 내용 요약: `@playwright/mcp` 서버를 `.mcp.json`에 추가하고 `enabledMcpServers`에 포함
- 검증 방법: MCP 서버 재연결 후 Playwright MCP 도구(예: `browser_navigate`, `browser_snapshot`, `browser_take_screenshot`)가 ToolSearch로 조회되는지 확인
- 롤백 방법: 추가한 항목 제거
- 예상 소요: 짧음

#### 단계 2: 루프 실습 — OrderStatusPage 대상 1회 시연
- 변경 파일: 없음 (사용자 피드백 내용에 따라 `frontend/src/pages/OrderStatusPage.tsx` 소폭 수정 가능)
- 변경 내용 요약: `npm run dev` 구동 상태에서 Playwright MCP로 화면 스크린샷 → 사용자에게 보여주고 설명 → 사용자가 평범한 말로 피드백 제공 → Claude가 코드 수정 → 재스크린샷으로 결과 확인. 이 과정 자체가 검증을 겸한다
- 검증 방법: 루프가 최소 1회 완결되는지 (스크린샷 → 피드백 → 수정 → 재확인)
- 롤백 방법: 단계 중 발생한 코드 변경을 git checkout으로 되돌림
- 예상 소요: 보통

#### 단계 3: skill 매뉴얼 작성
- 변경 파일: `.claude/skills/<skill-name>/SKILL.md` (이름은 단계 1~2 진행 후 확정 — 가칭 `frontend-design-feedback`)
- 변경 내용 요약: 트리거 조건, Playwright MCP 도구 사용법, 루프 절차(스크린샷→설명→피드백→수정→재확인), 기존 `frontend`/`frontend-testing` skill과의 관계를 문서화
- 검증 방법: 기존 SKILL.md들과 형식·트리거 조건 표현 일관성 육안 확인
- 롤백 방법: 추가된 skill 디렉토리 삭제
- 예상 소요: 짧음

### 리스크 및 대응
- 리스크 1: Playwright MCP가 별도 브라우저 바이너리 다운로드를 필요로 해 최초 실행이 느릴 수 있음 → 대응: 단계 1에서 최초 연결 시 지연 가능성을 미리 안내
- 리스크 2: MCP 서버 활성화가 기존 `enabledMcpServers`(`mysql`, `codex`) 동작에 영향을 줄 가능성 → 대응: 추가만 하고 기존 항목은 건드리지 않음, 등록 후 기존 MCP 도구 정상 동작 확인
- 리스크 3: "피드백 루프"는 본질적으로 사람이 직접 참여해야 하므로 자동화된 검증이 어려움 → 대응: 단계 2에서 실제 1회 시연으로 동작을 증명하고, skill 문서에 절차를 명확히 남겨 재현 가능하게 함

### 의존성
- `frontend`가 `npm run dev`로 로컬 구동 가능해야 함 (기존 완료된 작업의 산출물)
- Playwright MCP 서버 설치/실행 환경 (npx 기반, 브라우저 바이너리 자동 설치)
- 기존 `.mcp.json`/`.claude/settings.json` 구조 — 건드리는 범위를 최소화
