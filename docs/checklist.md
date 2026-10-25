# 체크리스트: 구매 플로우 프론트엔드 구현 (serverA 연동, 첫 mutation 화면)

- 마지막 업데이트: 2026-10-25 00:00

## 진행 상황
- [x] 단계 1: serverA 계약 작성 + 타입 생성
  - [x] 검증 통과 (`npm run generate:api:a` 성공, `tsc --noEmit` exit 0)
  - [ ] 코드리뷰 통과 (사용자 확인)
- [x] 단계 2: client-a.ts + CORS 프록시
  - [x] 검증 통과 (`tsc --noEmit` exit 0, `npm run build` exit 0)
  - [ ] 코드리뷰 통과
- [x] 단계 3: 상품 조회 화면
  - [x] 검증 통과 (`npm run build` exit 0, `/purchase/[goodsId]` Partial Prerender 확인)
  - [ ] 코드리뷰 통과
- [x] 단계 4: 구매 폼 구현
  - [x] 검증 통과 (`tsc --noEmit`/`npm run build` exit 0, 스크린샷으로 렌더링 확인)
  - [ ] 코드리뷰 통과
- [x] 단계 5: 로컬 E2E 검증
  - [x] 검증 통과 (상품조회→구매→주문상태 스크린샷, CORS 프록시 네트워크 요청 확인, 백엔드 Saga Phase1 로그 확인)
  - [ ] 코드리뷰 통과
- [x] 단계 6: 테스트 작성
  - [x] 검증 통과 (Vitest 9/9 통과, Playwright E2E 7/7 통과 — 둘 다 Claude가 직접 실행)
  - [ ] 코드리뷰 통과
- [x] 단계 7: serverA 계약 드리프트 테스트
  - [x] 검증 통과 (`compileTestJava` exit 0)
  - [ ] 코드리뷰 통과

## 최종 검증
- [x] `npx tsc --noEmit`, `npm run build` 통과
- [x] 로컬 E2E로 상품조회→구매→주문상태 전체 흐름 확인
- [x] CORS 프록시 정상 동작 확인
- [x] Vitest 9/9, Playwright E2E 7/7 통과
- [x] serverA `compileTestJava` 통과 (`gradlew test` 실행은 사용자 — bare-metal 기동 불안정 이력으로 직접 실행 보류)
- [x] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인 (git status로 대조)
- [x] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인 (`.gitignore`에 playwright-report/test-results 추가)

## 발견 사항 (작업 중 별도 처리 필요한 것)
- Cache Components 하에서 Client Component의 `useParams()`도 "동적 API"로 취급되어 Suspense 밖에서 쓰면 `CLIENT_HOOK_DYNAMIC` 빌드 에러가 남. `params`를 Promise prop으로 넘기고 `use()`로 푸는 공식 패턴(Suspense로 감쌈)으로 수정함 — `frontend` skill에 반영할지는 다음 skill 정리 때 검토.
- **MSW가 `openapi-fetch` 기반 클라이언트를 못 가로채는 버그를 찾아서 고침** — `openapi-fetch`의 `createClient()`가 `globalThis.fetch`를 호출 시점에 기본값으로 캡처하는데(`fetch: baseFetch = globalThis.fetch`), `server.listen()`을 `beforeAll` 안에 두면 이미 테스트 파일(및 `clientA`)이 다 import된 뒤에 실행돼서 패치 전의 fetch를 캡처해버림. `test/setup.ts`에서 `server.listen()`을 `beforeAll` 콜백이 아니라 **setup 파일 최상위에서 즉시 호출**하도록 고쳐서 해결 — `frontend-testing` skill 2-1/2-2절에 반영 완료.
- **serverA는 `gradlew bootRun`(bare-metal)으로 로컬 기동이 안 됨** — 원인 불명(앱 로그가 전혀 안 찍히고 조용히 exit 1). 반드시 `docker compose -f docker-compose.yml up -d server-a`로 띄워야 함(discovery-service/server-b/pyroscope-agent-init 등 의존성이 거기 맞춰져 있음).
- **`serverA/Dockerfile`이 소스를 빌드하지 않고 `build/libs/*.jar`를 그대로 복사**하는 구조라, 코드를 바꿔도 `gradlew :serverA:bootJar`로 미리 jar를 새로 만들지 않으면 도커 이미지가 오래된 코드로 뜬다 — 실제로 이번에 이것 때문에 `GET /api/v1/goods/{id}`가 404 매칭 자체가 안 되는 문제를 겪음(구버전 jar에 해당 핸들러가 없었던 것으로 추정). 앞으로 serverA를 docker로 띄우기 전엔 항상 `bootJar`부터 최신으로 만들어야 함 — serverB도 같은 구조인지 확인 필요.
