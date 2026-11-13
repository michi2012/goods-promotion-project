# 계획서: 작업 규칙 정비 (범용 e2e skill + 프로젝트 규칙 + 개인 환경 규칙 분리 + 백로그 관리)

- 작성일: 2026-11-13 (보완: 백로그 관리 추가)
- 관련 이슈/티켓: 없음 (결제 금액 기본기 작업 회고 — E2E가 "화면 이동"만 확인해 CDC 고장을 놓친 문제, 즉석 명령 검증의 재현성 문제, 범위 밖 발견 사항이 다음 /plan 때 덮어써져 사라지는 문제)
- 브랜치: `chore/e2e-guidelines` (main 기준, 별도 PR)

## 목표
1. E2E를 "최종 결과까지 확인하고, 저장소 스크립트로 고정하고, 근거와 함께 보고"하는 규칙으로 정비한다. 범용 원칙은 다른 프로젝트에서도 쓸 수 있는 skill로, 이 프로젝트 전용 내용은 `CLAUDE.md`로, 이 PC의 셸 규칙은 개인 파일로 나눈다.
2. 로드맵·후속 작업·범위 밖 발견 사항을 **사라지지 않는 단일 백로그**에서 관리한다. 위치는 설정값 하나로 정한다(지금은 `docs`, 이슈 트래커 도입 시 전환).

## 성공 기준
- [ ] `.claude/skills/e2e/SKILL.md`가 특정 DB·서비스·경로 없이 범용 원칙과 Playwright API·UI 패턴을 담는다 (프로젝트 고유 명칭 검색 0건: `serverA|serverB|serverC|Debezium|orders|payments|promotion`)
- [ ] `CLAUDE.md`의 "커밋 전 E2E 테스트 기준"이 "E2E 테스트 원칙 (이 프로젝트 적용)"으로 교체되고, "작업 시작 시"에 e2e skill 사용 지시가 추가된다. 기존 "로컬 검증이 불가능할 때" 기준은 그대로 유지
- [ ] `CLAUDE.md`에 "백로그 관리" 섹션(백로그 위치 설정값 `docs`, 조건별 동작, 작업 흐름 규칙)이 있다
- [ ] `docs/backlog.md`에 로드맵·후속 작업·버그·기술 부채·검증 대기 항목이 분류·우선순위·출처와 함께 정리돼 있다 (지금까지 흩어진 발견 사항 누락 없음)
- [ ] `CLAUDE.md`에서 "Windows + PowerShell 환경. bash 명령어 사용 금지." 문구가 빠지고, 같은 규칙과 Windows 주의사항이 `CLAUDE.local.md`(git 미추적)에 있다
- [ ] 메모리의 결제 로드맵이 `docs/backlog.md`를 가리키는 안내로 바뀐다 (내용 중복 없음)

## 비범위 (Out of Scope)
- E2E 스크립트·사전 점검 스크립트 구현, Playwright 구매 테스트 강화 (작업 B)
- 프론트 CI 추가, 백엔드 CI의 H2 환경변수 정리, PR CI 속도·격리 개선 (작업 B)
- "테스트 분업 원칙"의 "프론트는 CI 없음" 전제 수정 (작업 B에서 프론트 CI를 붙일 때)
- `/plan` skill 문서 자체 수정 (Windows 전용 지시 정리 포함 — 백로그로 등록)
- 이슈 트래커(Linear 등) 연동
- 사용자 전역(`~/.claude/skills`) 복사

## 단계별 작업 계획

### 단계 1: 범용 e2e skill 작성
- 변경 파일: `.claude/skills/e2e/SKILL.md` (신규)
- 변경 내용 요약: frontmatter(name, 발동 조건) + 층 구분(API E2E / UI E2E / 배포 후 스모크), 스크립트 고정, 사전 점검, 실행 시점(로컬·PR CI·머지 후/야간·배포 후), 결과 보고(근거 첨부·사용자 확인), 블랙박스 원칙(DB 직접 조회 대신 API로 최종 결과 확인), Playwright `request`·`expect.poll` 패턴 예시, 자가 점검.
- 검증 방법: 프로젝트 고유 명칭 grep 0건, 기존 skill 형식(frontmatter)과 일치 육안 확인
- 롤백 방법: 파일 삭제
- 예상 소요: 보통

### 단계 2: CLAUDE.md 프로젝트 규칙 정비
- 변경 파일: `CLAUDE.md`, `.claude/skills/e2e/project.md` (신규 — 사용자 승인으로 추가: E2E 프로젝트 적용 값은 매 대화 로드되는 CLAUDE.md 대신 skill 폴더에 둠)
- 변경 내용 요약:
  - "빌드 환경": Windows·PowerShell 문구 제거 (`CLAUDE.local.md`로 이동)
  - "작업 시작 시": e2e skill 사용 지시 추가
  - "커밋 전 E2E 테스트 기준" → "E2E 테스트 원칙 (이 프로젝트 적용)": 시나리오·스크립트 위치(작업 B에서 생성 예정 경로), 사전 점검 대상, 실행 시점 표(`DEPLOY_ENABLED` 포함), 기존 "로컬 검증이 불가능할 때" 표 유지
  - **"백로그 관리" 섹션 신규**: 백로그 위치 설정값(`docs` 기본 / 이슈 트래커 전환 시 변경), 조건별 동작(동시 기록 금지 — 원본은 한 곳), 작업 흐름(백로그에서 선택 → 진행 중 표시 → 작업 중 발견 사항은 checklist에 임시 기록 → 커밋 전 백로그로 이전 → 완료 시 PR 링크와 함께 완료로 이동), 전환 절차
  - "docs/ 파일 관리 원칙"에 "발견 사항은 커밋 전 백로그로 이전" 1줄 추가
- 검증 방법: `git diff CLAUDE.md` 육안 확인 — 대상 외 섹션 무변경
- 롤백 방법: `git checkout -- CLAUDE.md`
- 예상 소요: 보통

### 단계 3: 백로그 신규 작성 + 메모리 정리
- 변경 파일: `docs/backlog.md` (신규), 메모리 `project_payment_roadmap.md`·`MEMORY.md` (저장소 밖)
- 변경 내용 요약: 진행 중 / 진행 예정(우선순위순, 분류: 로드맵·후속 작업·버그·정합성·기술 부채·검증 대기, 출처) / 완료(PR 링크) 섹션. 지금까지 흩어진 항목 정리 — 결제 2·3단계, 작업 B, 금액 필수화, 배포 후 스모크, CDC 유실 대비 재대사, 중복 망취소 PG 응답 미검증, UNKNOWN 장기화 알람, `values-local.yaml` DB명, orders 페이지 lint, Helm `EUREKA_DEFAULT_ZONE`, CI H2 환경변수, `/plan` skill Windows 지시, EKS 재가동 후 검증 묶음 등. 메모리는 "로드맵·남은 작업은 `docs/backlog.md` 참조" + 사용자 학습 맥락만 남김.
- 검증 방법: 이전 checklist·보고의 발견 사항과 대조해 누락 확인, 메모리에 로드맵 상세 중복 없음
- 롤백 방법: `docs/backlog.md` 삭제, 메모리 파일 이전 내용 복원
- 예상 소요: 보통

### 단계 4: 개인 환경 규칙 분리
- 변경 파일: `CLAUDE.local.md` (신규, git 미추적)
- 변경 내용 요약: Windows + PowerShell, bash 금지 규칙 + 주의사항(파이프 BOM, `$?` vs `$LASTEXITCODE`, 큰따옴표 커밋 메시지는 `git commit -F`).
- 검증 방법: `git status --short`에 나타나지 않음, `git check-ignore CLAUDE.local.md` 확인
- 롤백 방법: 파일 삭제
- 예상 소요: 짧음

## 리스크 및 대응
- 리스크: 셸 규칙을 `CLAUDE.local.md`로 옮기면 이 PC가 아닌 곳(다른 PC, 클라우드 세션)에서는 적용되지 않음 → 대응: 의도한 동작(팀원·환경별 셸이 다름). 이 PC에서는 세션 시작 시 자동 로드
- 리스크: `CLAUDE.md`가 아직 없는 E2E 스크립트 경로를 가리킴 → 대응: "작업 B에서 생성 예정"으로 명시하고, 작업 B PR에서 실제 경로와 맞춤
- 리스크: skill과 `CLAUDE.md` 내용 중복 → 대응: skill은 원칙, `CLAUDE.md`는 적용 값(경로·대상·스위치)만
- 리스크: 백로그와 메모리·checklist에 같은 내용이 남아 어긋남 → 대응: 백로그를 원본으로 지정, 메모리는 포인터만, checklist 발견 사항은 커밋 전 이전 규칙
- 리스크: 백로그 정리 중 과거 발견 사항 누락 → 대응: 이번 대화의 보고·checklist 이력(git log)과 대조

## 의존성
- 없음 (문서·설정 파일만 변경, 코드·의존성 무변경)
