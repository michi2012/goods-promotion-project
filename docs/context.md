# 맥락 노트: 필수 체크 대비 CI result job + main 브랜치 보호 (BL-019)

## 왜 이 방식을 선택했는가
- GitHub 필수 체크는 "실행되지 않은 체크"를 대기 상태로 두어 머지를 막는다. `backend-ci`·`frontend-ci`는 워크플로 단위 `paths` 필터라 문서 전용 PR에선 아예 실행되지 않음 → 필수로 지정할 수 없었다.
- 해결: 워크플로는 PR마다 항상 실행하고, job 안에서 변경 여부를 판단해 테스트를 건너뛴다. **skipped job은 필수 체크에서 통과로 인정**되지만, matrix 9개를 각각 필수로 지정하면 관리가 번거로우므로 항상 실행되는 result job 하나를 워크플로마다 둔다.
- BL-001의 "문서만 바뀌면 CI를 돌리지 않는다" 결정을 유지하기 위해, 경로 필터 제거(항상 전체 실행)는 택하지 않았다 (사용자 결정 2026-11-13).
- 변경 감지는 `dorny/paths-filter` (사용자 결정). BL-001에서 이 action을 "모듈별 matrix 구성" 용도로는 기각했지만, 이번엔 "백엔드 변경 있음/없음" 한 가지만 판단하므로 공통 설정 변경 규칙을 직접 관리할 부담이 없다. 모듈별 판단은 여전히 Gradle 빌드 캐시가 맡는다.
- `dev` 정리는 CI 두 개만 (사용자 결정). `ci-cd.yaml`의 dev 환경·`create-pr`은 배포 파이프라인 설계라 EKS 재가동 시 함께 다룬다.
- 보호 규칙 설정(사용자 결정, Claude가 gh api로 적용): PR 필수 / 승인 0개(1인 저장소는 자기 PR 승인 불가) / 필수 체크 `backend-ci-result`·`frontend-ci-result` / 최신 브랜치 필수 끔(1인 작업엔 번거로움만) / 관리자도 적용(우회 방지, 급하면 설정에서 해제).

## 검토했으나 채택하지 않은 대안
### 대안 A: 경로 필터 제거, 항상 전체 실행
- 무엇: `paths` 삭제. 가장 단순.
- 왜 안 썼나: 문서 PR에도 백엔드 job 9개+프론트가 돌아 러너 낭비. BL-001 결정과 충돌.
### 대안 B: 직접 `git diff` 스크립트로 변경 감지
- 무엇: 외부 action 없이 셸로 경로 매칭.
- 왜 안 썼나: 기존 glob 목록(부정 패턴 포함)을 셸로 다시 구현해야 함. 코드 길어지고 실수 여지.
### 대안 C: 같은 이름의 "skip용" 더미 워크플로 (`paths-ignore`로 반대 조건)
- 무엇: GitHub 옛 문서의 우회책. 같은 job 이름을 가진 워크플로를 하나 더 둠.
- 왜 안 썼나: 두 파일의 경로 목록을 정확히 반대로 유지해야 하고, 둘 다 실행되는 PR에서 결과가 섞일 수 있음.
### 대안 D: 워크플로 하나(`ci.yml`)로 통합해 result 1개만 필수
- 왜 안 썼나: BL-001에서 백엔드·프론트 CI를 분리(소유·병렬)한 결정과 충돌, 변경 범위가 커짐.

## 기존 코드베이스 컨벤션
- 워크플로: `.github/workflows/*.yml`, 파일 상단 주석으로 실행 조건·이유 설명, `concurrency`로 같은 ref 이전 실행 취소
- 백엔드 CI는 모듈별 matrix + `--build-cache`, `fail-fast: false`

## 관련 파일/위치
- `.github/workflows/backend-ci.yml` — 백엔드 모듈 테스트 (PR + main push 캐시 채우기)
- `.github/workflows/frontend-ci.yml` — 프론트 lint·Vitest·build
- `.github/workflows/ci-cd.yaml` — 머지 시 배포(이번 범위 밖)
- `docs/backlog.md` — BL-019

## 외부 참조
- GitHub Docs: 필수 상태 체크와 skipped job 처리 ("Handling skipped but required checks")
- https://github.com/dorny/paths-filter (v4.0.3)
