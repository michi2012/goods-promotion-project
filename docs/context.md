# 맥락 노트: main CI 정상화

## 왜 이 방식을 선택했는가
PR #22 머지 후 확인 결과 (2026-11-13):
- PR #22의 CI `test` job은 **serverB `OpenApiContractTest` 실패**(NPE, `OpenApiContractTest.java:67`)로 빨간 채 머지됐다. serverA에서 고친 것과 같은 원인 — springdoc이 produces 미지정 응답을 `*/*`로 기술해 테스트가 `content."application/json"`을 못 찾음. Gradle이 serverB에서 멈춰 serverC·user-service 테스트는 CI에서 실행되지 않고 있었다. 이 상태면 앞으로 모든 PR의 test job이 실패한다.
- 머지 시 배포 파이프라인은 `image-build`가 "Configure AWS Credentials"에서 실패(EKS·AWS 자격 증명 비활성) → `deploy` skipped. 배포·ECR push는 없었지만 매 머지마다 실패로 표시된다(PR #21도 동일).

사용자 결정: main CI를 먼저 초록색으로 되돌리는 작은 PR(`fix/main-ci`)을 E2E 규칙 정비(작업 A)보다 먼저 올린다. EKS가 꺼져 있으므로 배포 스위치를 둔다.

- serverB 수정은 serverA와 같은 설정 1줄로 통일.
- 배포 스위치는 `set-environment` 조건 하나에 둔다. 머지 시 파이프라인의 시작점이라 이후 job(`image-build`·`deploy`·`create-pr`)이 `needs`로 연쇄 skipped된다. 기본값은 **꺼짐**(변수 미설정 = 배포 안 함) — 실수로 배포되는 것보다 배포가 안 되는 쪽이 안전.

## 검토했으나 채택하지 않은 대안
### 대안 A: serverB 테스트에서 `*/*` 허용
- 왜 안 썼나: serverA와 해결 방식이 달라지고, 생성 스펙이 실제(JSON)와 계약에 안 맞는 상태로 남는다.

### 대안 B: 각 job(`image-build`, `deploy`)마다 조건 추가
- 왜 안 썼나: 조건이 여러 곳에 흩어진다. 시작점 하나로 충분.

### 대안 C: 배포 기본값을 켜짐으로 (`vars.DEPLOY_ENABLED != 'false'`)
- 왜 안 썼나: 변수를 만들어 꺼야 하므로 지금 당장 저장소 설정 변경이 필요하고, 실수 시 배포가 나간다.

## 기존 코드베이스 컨벤션
- CI/CD: `.github/workflows/ci-cd.yaml` — PR opened/synchronize → `test`, merged → `set-environment` → `image-build` → `deploy` → (`dev`면) `create-pr`
- springdoc 설정 예: `serverA/src/main/resources/application.yaml`

## 관련 파일/위치
- `serverB/src/main/resources/application.yaml`
- `serverB/src/test/java/promotion/serverB/contract/OpenApiContractTest.java` (변경 없음)
- `.github/workflows/ci-cd.yaml`

## 외부 참조
- GitHub Actions `vars` 컨텍스트, job `needs` 연쇄 skip
