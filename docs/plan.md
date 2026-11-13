# 계획서: 필수 체크 대비 CI result job + main 브랜치 보호 (백로그 BL-019)

- 작성일: 2026-11-13
- 관련 이슈/티켓: 백로그 BL-019

## 목표
경로 필터로 `backend-ci`·`frontend-ci`가 실행되지 않는 PR(문서 전용 등)에서도 항상 결과를 내는 result job을 두고, 그 두 job을 필수 체크로 하는 main 브랜치 보호를 적용한다. CI 트리거의 존재하지 않는 `dev` 브랜치는 정리한다.

## 성공 기준
- [ ] 이 PR(워크플로 변경)에서 `backend-ci`의 테스트 9개와 `frontend-ci`의 check가 실행되고, `backend-ci-result`·`frontend-ci-result`가 성공한다 (PR 체크 화면 / `gh pr checks`)
- [ ] main 보호 규칙이 적용된다: PR 필수, 승인 0개, 필수 체크 `backend-ci-result`·`frontend-ci-result`, 최신 브랜치 필수 끔, 관리자도 적용 (`gh api repos/{owner}/{repo}/branches/main/protection`)
- [ ] 문서 전용 PR에서 테스트 job은 skipped, result 2개는 성공 → 머지 가능 상태가 된다 (`gh pr checks`, `gh pr view --json mergeStateStatus`)
- [ ] `backend-ci`·`frontend-ci`의 `pull_request.branches`에 `dev`가 없다
- [ ] main push 시 `backend-ci`(Gradle 캐시 채우기)는 기존처럼 백엔드 경로가 바뀔 때만 실행된다

## 비범위 (Out of Scope)
- `ci-cd.yaml`(배포 파이프라인)의 dev 환경 분기·`create-pr` job 정리 → EKS 재가동 전엔 실행 검증 불가. 백로그에 기록
- 모듈별로 바뀐 것만 테스트하는 matrix 구성 (Gradle 빌드 캐시가 담당, BL-001 결정 유지)
- 전체 스택 E2E CI (BL-007)

## 단계별 작업 계획

### 단계 1: backend-ci에 변경 감지·result job 추가
- 변경 파일: `.github/workflows/backend-ci.yml`
- 변경 내용 요약: `pull_request`의 `paths`를 없애고 `branches: [main]`으로. `changes` job(`dorny/paths-filter` v4.0.3, 커밋 SHA 고정)이 기존 paths 목록으로 백엔드 변경 여부를 출력 → `test` matrix는 `needs: changes` + 백엔드 변경 시에만 실행 → `backend-ci-result` job(`if: always()`)이 needs 중 failure/cancelled가 있으면 실패, 나머지(success/skipped)는 성공. `push`(main)는 기존 paths 필터 유지.
- 검증 방법: YAML 문법·액션 검사 (`docker run --rm -v <repo>:/repo -w /repo rhysd/actionlint:<버전>` — 불가하면 YAML 파싱만) + 단계 4 실제 실행
- 롤백 방법: `git checkout -- .github/workflows/backend-ci.yml`
- 예상 소요: 짧음

### 단계 2: frontend-ci에 같은 구조 적용
- 변경 파일: `.github/workflows/frontend-ci.yml`
- 변경 내용 요약: 단계 1과 같은 구조. `changes` → `check`(조건부) → `frontend-ci-result`. `branches: [main]`.
- 검증 방법: 단계 1과 동일
- 롤백 방법: `git checkout -- .github/workflows/frontend-ci.yml`
- 예상 소요: 짧음

### 단계 3: 문서 갱신
- 변경 파일: `docs/backlog.md`(ci-cd.yaml dev 흔적 항목 추가, BL-019 진행 상태), 필요 시 `docs/design-notes.md`
- 검증 방법: 내용 확인
- 롤백 방법: `git checkout -- <파일>`
- 예상 소요: 짧음

### 단계 4: 브랜치 push + PR → 실제 CI 실행 확인 (높은 리스크: 커밋·푸시 — 승인 후)
- 변경 내용 요약: 기능별 커밋 → push → PR 생성. 워크플로 파일이 바뀌었으므로 두 CI 모두 테스트까지 실행돼야 함.
- 검증 방법: `gh pr checks <PR>` — test 9개·check·result 2개 성공
- 롤백 방법: 수정 커밋 추가 (PR 머지 전이므로 main 영향 없음)
- 예상 소요: 보통 (CI 대기)

### 단계 5: (사용자 머지 후) main 브랜치 보호 적용 (높은 리스크: 외부 설정 — 명령 보여주고 재확인 후)
- 변경 내용 요약: `gh api -X PUT repos/{owner}/{repo}/branches/main/protection`로 PR 필수·승인 0·필수 체크 2개(strict false)·enforce_admins true 적용.
- 검증 방법: `gh api repos/{owner}/{repo}/branches/main/protection`로 설정 조회
- 롤백 방법: `gh api -X DELETE repos/{owner}/{repo}/branches/main/protection`
- 예상 소요: 짧음

### 단계 6: 문서 전용 PR로 skip 동작 검증
- 변경 내용 요약: 백로그 BL-019를 "완료"(PR 링크)로 바꾸는 문서 전용 PR 생성 → 테스트 job skipped, result 2개 성공, 머지 가능 확인.
- 검증 방법: `gh pr checks`, `gh pr view --json mergeStateStatus`(CLEAN 기대)
- 롤백 방법: 실패 시 보호 규칙 삭제(단계 5 롤백) 후 원인 수정
- 예상 소요: 짧음

## 리스크 및 대응
- 리스크: paths-filter는 picomatch 기반이라 기존 `paths`의 부정 패턴 `'!frontend/**'`가 그대로는 "프론트 외 전부"로 매칭됨 → 대응: paths-filter v4의 `predicate-quantifier: some-with-excludes`로 같은 의미 유지(구현 시 확인), 단계 4·6의 실제 실행 결과(백엔드 PR → true, 문서 PR → false)로 확인
- 리스크: 두 워크플로 result job 이름이 같으면 필수 체크 이름이 겹침 → 대응: `backend-ci-result`·`frontend-ci-result`로 구분
- 리스크: 테스트 실패 시 result가 실패하는지는 이번에 실제로 재현하지 않음 → 대응: GitHub 표준 패턴(`contains(needs.*.result, 'failure')`) 사용, 이후 실제 실패 PR에서 자연 확인
- 리스크: 보호 규칙 적용 후 설정 실수로 머지가 막힘 → 대응: 관리자가 설정에서 즉시 수정·삭제 가능(단계 5 롤백)

## 의존성
- GitHub Action `dorny/paths-filter` v4.0.3 (서드파티, SHA 고정 — 사용자 승인 완료)
- `gh` CLI 인증(저장소 admin 권한)
