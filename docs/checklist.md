# 체크리스트: 프론트엔드 전면 재작성 — Next.js + Contract-first API

- 마지막 업데이트: 2026-10-24 00:00

## 진행 상황
- [x] 단계 1: skill 문서 재작성 (frontend/frontend-testing/api)
  - [x] 검증 통과 (육안 확인 — Next.js 구조/contract-first/하이브리드 페칭/Prism/테스트 피라미드/CI drift-check 참조 모두 반영 확인)
  - [ ] 코드리뷰 통과 (사용자 확인)
- [x] 단계 2: 기존 frontend/ 삭제 + Next.js 신규 scaffold
  - [x] 검증 통과 (`npm run build` exit 0, `npm run dev` 기동 후 `localhost:3000` 200 응답 확인)
  - [ ] 코드리뷰 통과
- [x] 단계 3: contracts/openapi.yaml 작성 + 타입 생성 + fetch 래퍼
  - [x] 검증 통과 (`npm run generate:api` 성공, `tsc --noEmit` exit 0, `npm run build` exit 0)
  - [ ] 코드리뷰 통과
- [x] 단계 4: shadcn/ui 초기화 + 공통 레이아웃
  - [x] 검증 통과 (`npm run build` exit 0, dev 서버에서 header/footer 렌더 확인)
  - [ ] 코드리뷰 통과
- [x] 단계 5: 검색 폼(Client Component) + 조회 결과(Server Component) + CORS 불필요 확인
  - [x] 검증 통과 (redis-b+serverB 로컬 기동 → PAID/NOT_FOUND 실제 조회 확인, `client` import가 Server Component에만 있어 브라우저가 8081을 직접 호출하지 않음을 코드로 확인)
  - [ ] 코드리뷰 통과
- [x] 단계 6: 테스트 작성 (Vitest+RTL / Playwright E2E+axe-core+시각회귀)
  - [x] 검증 통과 (작성 완료, `tsc --noEmit`/`npm run build` exit 0, 실행은 사용자 안내)
  - [ ] 코드리뷰 통과
- [x] 단계 7: Prism mock + CI drift-check
  - [x] 검증 통과 (`npm run mock:api` 응답 확인, `NEXT_PUBLIC_API_MODE=mock` 전환 E2E 확인, serverB `compileTestJava` exit 0)
  - [ ] 코드리뷰 통과

- CI drift-check 구현 방식을 계획(ci-cd.yaml에 별도 diff 스텝 추가)에서 바꿈 — serverB에 `OpenApiContractTest`(JUnit, `/v3/api-docs` vs `contracts/openapi.yaml` 비교)를 추가해서, 기존 CI의 `test` job이 이미 돌리는 `./gradlew clean test`에 자동으로 포함되게 함. 새 Gradle 의존성도 필요 없었음(snakeyaml이 이미 테스트 클래스패스에 있음 확인). ci-cd.yaml은 전혀 수정하지 않음 — CI 파이프라인 변경이라는 고위험 작업을 피하면서 동일한 검증 효과를 얻음.

## 최종 검증
- [ ] 모든 단위/컴포넌트 테스트 통과
- [ ] E2E 테스트 통과 (접근성 0 violations, 시각회귀 스냅샷 포함)
- [ ] `npm run build` 타입체크 통과
- [ ] 로컬 serverB 기동 상태에서 브라우저 수동 E2E 확인 (CLAUDE.md UI 검증 원칙)
- [ ] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인
- [ ] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인

## 발견 사항 (작업 중 별도 처리 필요한 것)
- create-next-app이 깐 Next.js 16.4.3은 `cacheComponents`/`partialPrefetching`이 기본 활성화됨(Cache Components/PPR). `params`가 Promise로 바뀌고, 캐시 안 되는 동적 데이터는 `<Suspense>` 없이 쓰면 prerender 검증 오류가 남 — 단계 1에서 작성한 `frontend/SKILL.md` 3번 섹션 예시를 Suspense+내부 async 컴포넌트 패턴으로 수정함 (사용자 확인: A안으로 진행).
- `npm run build` 시 "Next.js ignored yarn.lock in C:\Users\michi because it is outside the current Git repository" 경고 발생 — `C:\Users\michi`에 있는 무관한 yarn.lock을 Turbopack이 워크스페이스 루트 추정 중 발견해서 나는 경고. 빌드는 exit 0으로 정상 통과했고 기능에 영향 없음 — 손대지 않음 (원하면 `turbopack.root` 설정으로 끌 수 있음).
- 계획(plan.md 단계 6)엔 MSW 핸들러(`src/mocks/handlers.ts`)도 포함돼 있었으나, 실제 구현 결과 이 화면엔 Client Component가 API를 직접 호출하는 조작(mutation)이 전혀 없음(검색 폼은 `router.push`만 함) — `frontend-testing` skill 규칙상 MSW는 Client Component의 조작만 모킹 대상이라, 모킹할 대상이 없어 해당 파일은 작성하지 않음. 추후 삭제/생성 같은 조작 화면이 추가되면 그때 작성.
- `npm install`에서 npm 10.8.2의 알려진 Arborist 버그("Cannot read properties of null (reading 'edgesOut')")로 vitest 설치가 반복 실패 — `--legacy-peer-deps`로 우회해 설치 완료. 이 과정에서 `@testing-library/dom`이 lockfile에 누락되는 2차 증상이 있었고 명시적으로 추가 설치해 해결함. 사용자가 나중에 클린 설치(`node_modules`/`package-lock.json` 삭제 후 `npm install`) 시 같은 버그가 재발할 수 있음 — 재발하면 `--legacy-peer-deps`를 쓰거나 npm을 최신 버전으로 올리는 걸 권장.
