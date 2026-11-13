# 맥락 노트: 작업 규칙 정비 (E2E + 백로그)

## 왜 이 방식을 선택했는가
결제 금액 기본기 작업(PR #22)을 검증하면서 드러난 문제:
- **UI E2E가 최종 결과를 확인하지 않았다.** Playwright 구매 테스트는 "주문 상태 페이지로 이동 + '의 상태:' 텍스트"까지만 확인해, Debezium 커넥터가 고장 나 주문이 PENDING에서 멈춘 상태에서도 통과했다.
- **서버 간 흐름 검증을 매번 즉석 명령으로 했다.** 재현성이 없고, PowerShell BOM·stderr·Git Bash 경로 변환 문제로 반복해서 헛돌았다.
- **환경 점검 없이 E2E를 돌렸다.** `redis-b` 누락, 커넥터 DB명 오류, 오프셋 불일치를 E2E 실패 후에야 발견했다.
- **검증 근거 보고가 일정하지 않았다.** 스크린샷 확인(frontend skill 자가 점검 항목)을 건너뛴 적이 있다.

사용자 결정 (2026-11-13):
- E2E 원칙은 **범용 skill**(`.claude/skills/e2e/`)로 두어 다른 프로젝트에서도 재사용. 저장소에만 둔다(전역 복사 안 함 — 사본 하나로 관리).
- 팀 운영을 가정해 **셸 규칙은 개인 파일(`CLAUDE.local.md`)로** 이동. 팀원 OS가 다르므로 저장소 규칙에 특정 셸을 강제하지 않는다. 공유 E2E 스크립트는 셸에 의존하지 않는 Playwright(TypeScript)로 작성(작업 B).
- CI는 단계적으로: PR마다 빠른 테스트, 머지 후·야간·수동으로 전체 스택 E2E, 배포 후 스모크(배포 스위치가 켜졌을 때만).
- 브랜치 분리: 이 작업은 `chore/e2e-guidelines`(main 기준) 별도 PR. (`DEPLOY_ENABLED`와 serverB 계약 테스트는 PR #22가 먼저 머지되어 `fix/main-ci` PR #23으로 처리 완료.) 작업 B는 이 작업 이후.

### 백로그 관리 (보완, 2026-11-13)
- 문제: 범위 밖 발견 사항을 checklist "발견 사항"에 적는데, 커밋 후 다음 `/plan` 때 checklist를 새로 써서 목록에서 사라졌다(git 이력에만 남음). 3종 문서는 "작업 하나" 관리용이라 로드맵·남은 작업(PM/PO 영역) 관리에 맞지 않는다.
- 결정: **단일 백로그**를 둔다. 원래 계획한 일(로드맵·후속 작업)과 범위 밖 발견 사항(버그·기술 부채·검증 대기)을 한 목록에서 분류로 구분한다. 진행 중 작업의 세부는 3종 문서, 결정 이유는 `design-notes.md`가 담당.
- 위치는 `CLAUDE.md`의 **설정값 하나**로 정한다 — 지금은 `docs`(`docs/backlog.md`). 이슈 트래커를 쓰게 되면 설정을 바꾸고 미완료 항목을 옮긴 뒤 문서에는 링크만 남긴다. **동시 기록 금지**(원본은 한 곳). 트래커 연결 여부 자동 감지는 세션마다 달라질 수 있어 쓰지 않는다.
- 개인 프로젝트이고 팀이어도 트래커를 안 쓰는 경우가 있어 `docs`를 기본값으로 둔다.
- 메모리의 결제 로드맵은 백로그와 중복되므로 포인터로 바꾼다.

## 검토했으나 채택하지 않은 대안
### 대안 A: CLAUDE.md에 모든 E2E 규칙을 직접 작성
- 왜 안 썼나: 다른 프로젝트 재사용 요구. 범용 원칙과 프로젝트 적용 값이 섞임.

### 대안 B: 셸 규칙 유지(PowerShell 통일) 또는 공유 스크립트만 bash 허용
- 왜 안 썼나: 팀 가정 시 OS가 달라 특정 셸 강제가 맞지 않음. Playwright(Node)로 공유 스크립트를 쓰면 셸 문제 자체가 사라짐.

### 대안 C: 범용 E2E 프레임워크(시나리오 DSL) 직접 구현
- 왜 안 썼나: 과설계. 패턴 통일 + 기존 도구(Playwright `request`, `expect.poll`)로 충분.

## 기존 코드베이스 컨벤션
- skill 형식: `.claude/skills/{name}/SKILL.md`, frontmatter `name` + `description`(발동 조건) — 예: `.claude/skills/testing/SKILL.md`
- `CLAUDE.md` "작업 시작 시"에 skill별 사용 지시 목록
- `.gitignore`에 `CLAUDE.local.md` 이미 등록

## 관련 파일/위치
- `CLAUDE.md` — 프로젝트 규칙 ("빌드 환경", "작업 시작 시", "커밋 전 E2E 테스트 기준")
- `.claude/skills/frontend-testing/SKILL.md` — 기존 Playwright·시각 회귀·axe 규칙 (UI 테스트 세부는 여기 유지)
- `frontend/e2e/` — 기존 Playwright 스펙
- `.github/workflows/ci-cd.yaml` — CI/CD (작업 B 대상)

## 외부 참조
- Playwright API testing (`request` fixture), `expect.poll`
