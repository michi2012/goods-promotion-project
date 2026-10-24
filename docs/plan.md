# 계획서: 테스트 실행 정책 변경 + skill 문서 교정

- 작성일: 2026-10-24
- 관련 이슈/티켓: 없음
- 브랜치: chore/test-execution-policy

## 목표
CLAUDE.md의 테스트 분업 원칙을 "작성=Claude/실행=사용자" 고정 분리에서 "실행 비용·환경 의존성" 기준으로 바꾸고, 이전 외부 코드리뷰에서 합의된 skill 문서 오류(구식 Vite 기준, 번호 참조 오류, MSW 경로 불일치, client.ts 하드코딩)를 교정한다.

## 성공 기준
- [ ] `CLAUDE.md`의 테스트 분업 원칙이 비용/환경 기준으로 재작성되어 있다 (육안 확인)
- [ ] `.claude/skills/testing`, `frontend-testing` 두 skill에 구버전 문구("실행은 사용자", 옛 번호 참조)가 남아있지 않다 (grep 확인)
- [ ] `frontend-design-feedback` skill에 Vite 흔적(`localhost:5173`, `src/pages/`)이 남아있지 않다 (grep 확인)
- [ ] `frontend/src/lib/api/client.ts`가 `NEXT_PUBLIC_API_BASE_URL` 환경변수를 쓰고, 미설정 시 기존 동작(localhost:8081)과 동일하다 — `npx tsc --noEmit`, `npm run build` 통과
- [ ] 각 skill의 섹션 번호 참조가 실제 섹션 번호와 일치한다 (육안 확인)

## 비범위 (Out of Scope)
- `.github/workflows/ci-cd.yaml`에 프론트 테스트 job 추가 — 별도 논의/작업으로 분리
- 실제 MSW 핸들러 파일(`src/mocks/handlers.ts`) 작성 — 이 화면엔 아직 Client Component 조작이 없어 여전히 불필요, skill의 예시 코드만 교정
- Prism, CORS, 데이터 페칭 전략 등 기존 결정 재검토 없음

## 단계별 작업 계획

### 단계 1: CLAUDE.md 테스트 분업 원칙 재작성
- 변경 파일: `CLAUDE.md`
- 변경 내용 요약: "작성=Claude/실행=사용자" 표를 "외부 인프라 없이 빠르게 끝나는 테스트는 Claude가 직접 실행(방금 수정한 클래스/파일 단위로 필터링), 외부 인프라가 필요하거나 전체 스위트는 환경이 이미 떠 있을 때만 Claude가 실행하고 아니면 사용자에게 요청"으로 재작성. 디버깅 루프 탈출 조건(3회 연속 실패 시 중단)이 Claude 직접 실행에도 적용됨을 명시.
- 검증 방법: 육안 확인, 기존 "실행은 사용자 직접" 류 문구가 안 남았는지 grep
- 롤백 방법: git으로 되돌림
- 예상 소요: 짧음

### 단계 2: testing/SKILL.md(백엔드) 자가점검 수정
- 변경 파일: `.claude/skills/testing/SKILL.md`
- 변경 내용 요약: 자가점검의 "`./gradlew test` 통과하는가?"를 "새로 작성/수정한 테스트는 `--tests` 필터로 Claude가 직접 실행해 통과를 확인했는가? (Testcontainers 등 외부 인프라가 필요한 테스트는 환경이 떠 있을 때만)"로 수정
- 검증 방법: 육안 확인
- 롤백 방법: git으로 되돌림
- 예상 소요: 짧음

### 단계 3: frontend-testing/SKILL.md 실행 정책(4·5번) 재작성
- 변경 파일: `.claude/skills/frontend-testing/SKILL.md`
- 변경 내용 요약: "4. 실행 명령어" 섹션에서 "작성은 Claude, 실행은 사용자" 문구 제거. `npm run test`(Vitest)는 Claude가 직접 실행하는 것을 기본값으로 명시(프론트는 CI에 테스트 job이 없어 로컬 실행이 유일한 방어선이라는 이유 포함). `npm run test:e2e`(Playwright)는 로컬에 dev 서버+백엔드(또는 Prism)가 이미 떠 있으면 Claude가 직접 실행, 아니면 사용자에게 요청. 5번 자가점검도 맞춰 수정.
- 검증 방법: 육안 확인
- 롤백 방법: git으로 되돌림
- 예상 소요: 짧음

### 단계 4: frontend-design-feedback/SKILL.md 교정
- 변경 파일: `.claude/skills/frontend-design-feedback/SKILL.md`
- 변경 내용 요약: description과 1번 섹션의 `localhost:5173`→`localhost:3000`, `frontend/src/pages/`→`frontend/src/app/`로 수정. 3번 섹션의 "frontend skill 3절(shadcn)" 참조를 "4절"로 수정. 4번 섹션 근처에 "디자인 다듬기는 화면 구현 후, 시각 회귀 스냅샷을 찍기 전에 한다"는 순서 가이드 한 줄 추가.
- 검증 방법: grep으로 `5173`, `src/pages/` 미존재 확인, 육안으로 번호 참조 확인
- 롤백 방법: git으로 되돌림
- 예상 소요: 짧음

### 단계 5: frontend/SKILL.md 교정
- 변경 파일: `.claude/skills/frontend/SKILL.md`
- 변경 내용 요약: 3번 섹션의 "Simplicity First — 4번 폼/상태 원칙과 동일"을 "5번"으로 수정. 2번 섹션의 `client.ts` 예시 코드를 `NEXT_PUBLIC_API_BASE_URL` 환경변수 사용 버전으로 갱신(단계 6과 동일 내용 유지).
- 검증 방법: 육안 확인
- 롤백 방법: git으로 되돌림
- 예상 소요: 짧음

### 단계 6: client.ts 실제 코드 — 환경변수 분리
- 변경 파일: `frontend/src/lib/api/client.ts`
- 변경 내용 요약: `http://localhost:8081` 하드코딩을 `process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8081'`로 변경 (기본값 유지, 미설정 시 기존 동작과 동일).
- 검증 방법: `npx tsc --noEmit`, `npm run build` 통과
- 롤백 방법: git으로 되돌림
- 예상 소요: 짧음

### 단계 7: frontend-testing/SKILL.md — MSW/시각회귀 보강
- 변경 파일: `.claude/skills/frontend-testing/SKILL.md`
- 변경 내용 요약: 2번 섹션 MSW 핸들러 예시를 상대경로(`/api/v1/...`)에서 `client.ts`의 `baseUrl`과 동일한 절대 URL로 수정하고, "핸들러 경로는 반드시 `client.ts`의 `baseUrl`과 맞춰야 한다(origin이 다르면 jsdom에서 매칭 실패)"는 주의 문구 추가. 3-1번 시각 회귀 섹션에 "핵심 화면 1~2개로 제한" 가이드와 "로컬(Windows) 스냅샷은 플랫폼 종속적— CI(Linux)에서 쓰려면 별도 베이스라인 필요, 로컬 전용이면 무관"이라는 주의사항 추가.
- 검증 방법: 육안 확인
- 롤백 방법: git으로 되돌림
- 예상 소요: 보통

## 리스크 및 대응
- 리스크 1: "Claude가 직접 테스트 실행"이라는 새 규칙이 토큰/시간을 오히려 더 쓰게 할 가능성 → 대응: "방금 수정한 것만 필터링 실행"으로 범위를 좁혀서 비용을 제한
- 리스크 2: 여러 skill 파일에 흩어진 번호 참조를 이번에 다 못 잡을 가능성 → 대응: 단계별 검증에서 grep으로 옛 패턴(`5173`, `src/pages/`, 틀린 섹션번호) 존재 여부를 명시적으로 확인

## 의존성
없음 (기존 결정 재검토 없이 문서/소규모 코드 교정만)
