# 체크리스트: 프론트엔드 통합 기반 작업

- 마지막 업데이트: 2026-06-08

## 진행 상황
- [x] 단계 1: serverB에 springdoc-openapi 추가 (⚠️ 신규 Gradle 의존성 — 사용자 승인 필요)
  - [x] 검증 통과 (docker-compose 재빌드 → `curl http://localhost:8081/v3/api-docs` → HTTP 200, OrderStatusResponse 스키마 확인)
  - [ ] 코드리뷰 통과
- [x] 단계 2: React+TS+Vite 스캐폴딩 + shadcn/ui 설정
  - [x] 검증 통과 (`npm run build` 성공, `npm run dev` 구동 후 Playwright 스크린샷으로 shadcn/ui Card+Input+Button 렌더링 확인)
  - [ ] 코드리뷰 통과
- [x] 단계 3: orval + TanStack Query 연동 — 타입/훅 자동 생성 파이프라인
  - [x] 검증 통과 (`npm run generate:api` 실행 → `OrderStatusResponse` 타입, `useGetOrderStatus`/`useGetStockView` 훅 생성 확인, `npm run build` 통과)
  - [ ] 코드리뷰 통과
- [x] 단계 4: 검증 화면 구현 — 주문 상태 조회
  - [x] 검증 통과 (Playwright로 브라우저에서 주문 ID 입력 → 조회 클릭 → serverB 응답("NOT_FOUND") 화면 표시 확인. CORS 차단 발견 → Vite dev proxy로 해결)
  - [ ] 코드리뷰 통과
- [x] 단계 5: 프론트엔드 테스트 작성 (Vitest+RTL / MSW / Playwright)
  - [x] 검증 통과 (Claude가 스모크 검증: `npx vitest run` 3/3 통과, `npx playwright test` 3/3 통과 — 시각 회귀 스냅샷 1개 + axe-core 접근성 0 violations. 최종 실행은 사용자가 `npm run test`, `npm run test:e2e`로 재확인 권장)
  - [ ] 코드리뷰 통과
- [x] 단계 6: skill 매뉴얼 작성 (`frontend`, `frontend-testing`)
  - [x] 검증 통과 (기존 `api`/`testing`/`jpa` SKILL.md와 동일한 frontmatter·트리거 조건·"자가 점검" 형식으로 작성, 육안 확인)
  - [ ] 코드리뷰 통과

## 최종 검증
- [x] 모든 단위 테스트 통과 (Vitest — `npx vitest run` 3/3)
- [x] 모든 통합/E2E 테스트 통과 (MSW 통합 테스트 포함 Vitest 3/3, Playwright E2E 3/3 — 시각 회귀 1 + axe-core 접근성 0 violations)
- [x] 린터 통과 (ESLint — `npx eslint .` 0 errors. orval/shadcn 생성 코드(`api/generated/**`, `components/ui/**`)는 "직접 수정 금지" 컨벤션에 따라 `eslint.config.js`에서 제외 처리)
- [x] 타입 체크 통과 (`npx tsc -b` 에러 없음)
- [x] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인 (실제 비즈니스 화면/디자인 루프/Figma MCP/serverA·C 확장/CI-CD 모두 미작업)
- [x] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인 (`docs/*.md`, `serverB/build.gradle`, `frontend/`, `.claude/skills/frontend*` — 모두 plan 범위 내)

## 발견 사항 (작업 중 별도 처리 필요한 것)
- 단계 4에서 발견: serverB가 CORS 헤더를 보내지 않아 브라우저에서 직접 호출 시 차단됨. 이번엔 `vite.config.ts`의 dev proxy(`/api` → `http://localhost:8081`)로 우회 — 로컬 개발 환경 한정 해결책.
  - ⚠️ 운영 배포 시점에는 dev proxy가 동작하지 않으므로, serverB(및 다른 서버)에 `WebMvcConfigurer` 기반 CORS 설정을 별도로 추가해야 함. 이번 plan의 비범위이므로 후속 작업으로 분리 필요.
- 최종 검증(린터)에서 발견: orval/shadcn이 생성한 코드(`src/api/generated/**`, `src/components/ui/**`)가 ESLint 규칙(`react-hooks/immutability`, `react-refresh/only-export-components`)에 걸림. "생성물 직접 수정 금지" 컨벤션과 충돌하므로, `eslint.config.js`의 `globalIgnores`에 두 경로를 추가해 제외 처리함.

---

# 체크리스트: 디자인 다듬기 루프 (브라우저 MCP 기반 시각 피드백)

- 마지막 업데이트: 2026-06-08

## 진행 상황
- [x] 단계 1: Playwright MCP 서버 등록 (`.mcp.json`, `.claude/settings.json`)
  - [x] 검증 통과 (세션 재연결 후 ToolSearch로 `mcp__playwright__browser_*` 도구 22종 조회 확인)
  - [ ] 코드리뷰 통과
- [x] 단계 2: 루프 실습 — OrderStatusPage 대상 1회 시연
  - [x] 검증 통과 (Playwright MCP로 초기 화면 스크린샷 → 설명 → 사용자 피드백("홈페이지처럼 빈공간 채워줘") → `OrderStatusPage.tsx`에 header/footer 추가 → 재스크린샷 → 사용자 확인("이대로 하자")까지 루프 1회 완결)
  - [ ] 코드리뷰 통과
- [x] 단계 3: skill 매뉴얼 작성
  - [x] 검증 통과 (`.claude/skills/frontend-design-feedback/SKILL.md` 작성 — 기존 `frontend`/`frontend-testing` SKILL.md와 동일한 frontmatter·번호 섹션·"자가 점검" 형식)
  - [ ] 코드리뷰 통과

## 최종 검증
- [x] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인 (Figma MCP/시각 회귀 자동화 강화/신규 비즈니스 화면 모두 미작업)
- [x] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인 (`.mcp.json`(gitignore 대상)/`.claude/settings.json`/`frontend/src/pages/OrderStatusPage.tsx`/`.claude/skills/frontend-design-feedback/`/`docs/*.md`/`.gitignore` — 모두 plan 범위 내)
- [x] Vitest 컴포넌트+MSW 통합 테스트 (`npx vitest run`) — 3/3 통과, 헤더/푸터 변경 영향 없음
- [x] Playwright E2E (`npx playwright test --update-snapshots`) — 시각 회귀 스냅샷 1개 재생성 후 통과, 접근성(axe-core) 0 violations 통과. 단, "주문 ID 조회" 시나리오 1개는 serverB 미구동(502)으로 실패 — 이번 변경과 무관한 기존 환경 의존성 이슈

## 발견 사항 (작업 중 별도 처리 필요한 것)
- `.playwright-mcp/`(Playwright MCP의 스크린샷/콘솔로그/스냅샷 임시 출력 디렉토리)가 새로 생성됨 → `.gitignore`에 추가해 커밋 대상에서 제외
- 헤더/푸터 레이아웃 변경으로 기존 시각 회귀 베이스라인(`order-status-initial-chromium-win32.png`)이 깨져 `--update-snapshots`로 재생성함 — 정상적인 "의도적 UI 변경" 절차 (`frontend-testing` skill 3-1 참조)
- E2E "주문 ID 조회" 시나리오 테스트가 serverB 미구동으로 실패 — 기존에도 알려진 로컬 환경 의존성(plan.md 발견 사항의 CORS/serverB 이슈와 동일 계열), 이번 작업의 회귀는 아님
