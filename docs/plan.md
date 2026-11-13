# 계획서: main CI 정상화 (serverB 계약 테스트 + 배포 스위치)

- 작성일: 2026-11-13
- 관련 이슈/티켓: 없음 (PR #22 머지 후 main CI 테스트 실패 발견)
- 브랜치: `fix/main-ci` (main 기준)

## 목표
main의 CI 테스트 job을 다시 통과시키고, EKS가 꺼져 있는 동안 머지할 때마다 배포 파이프라인이 실패로 표시되지 않게 한다.

## 성공 기준
- [ ] serverB `OpenApiContractTest` 통과 (`gradlew :serverB:test --tests "promotion.serverB.contract.OpenApiContractTest"`, CI H2 환경변수 설정 상태 포함)
- [ ] 이 PR의 CI `test` job이 초록색 (serverC·user-service 등 serverB 이후 모듈 테스트까지 실행·통과)
- [ ] `DEPLOY_ENABLED`가 `'true'`가 아니면 머지 시 `set-environment`·`image-build`·`deploy`·`create-pr`이 실패가 아닌 skipped (워크플로 조건 검토 + 이 PR 머지 시 실제 실행 결과로 확인)

## 비범위 (Out of Scope)
- CI 테스트 job의 H2 환경변수 정리, 프론트 CI 추가 (작업 B)
- 배포 후 스모크 테스트 job (작업 B 이후)
- AWS 자격 증명·EKS 재가동
- 저장소 변수 `DEPLOY_ENABLED` 생성 (미설정 = 배포 꺼짐이 기본값이라 지금은 불필요)

## 단계별 작업 계획

### 단계 1: serverB springdoc 응답 콘텐츠 타입 기본값
- 변경 파일: `serverB/src/main/resources/application.yaml`
- 변경 내용 요약: `springdoc.default-produces-media-type: application/json` 추가 (serverA와 동일). produces 미지정 응답이 `*/*`로 기술되어 계약 테스트가 `application/json`을 못 찾던 문제.
- 검증 방법: `.\gradlew.bat :serverB:test --tests "promotion.serverB.contract.OpenApiContractTest"` (그대로 + CI H2 환경변수), 이어서 `.\gradlew.bat :serverB:test`
- 롤백 방법: `git checkout -- serverB/src/main/resources/application.yaml`
- 예상 소요: 짧음

### 단계 2: 배포 스위치 `DEPLOY_ENABLED` ⚠️ 인프라(CI/CD) 변경
- 변경 파일: `.github/workflows/ci-cd.yaml`
- 변경 내용 요약: `set-environment` 조건을 `github.event.pull_request.merged == true && vars.DEPLOY_ENABLED == 'true'`로 변경. 이 job을 `needs`로 기다리는 `image-build`·`deploy`·`create-pr`이 함께 skipped. 주석으로 스위치 의미와 켜는 방법(저장소 변수 `DEPLOY_ENABLED=true`) 명시.
- 검증 방법: 워크플로 YAML 문법 확인(파싱), job 의존 관계 검토. 실제 동작은 이 PR 머지 시 Actions 실행 결과로 확인
- 롤백 방법: `git checkout -- .github/workflows/ci-cd.yaml`
- 예상 소요: 짧음

## 리스크 및 대응
- 리스크: serverB 이후 모듈(serverC, user-service 등) 테스트가 CI에서 오랜만에 실행되어 다른 실패가 드러남 → 대응: 이 PR CI 결과로 확인, 드러나면 정지 후 보고(범위 결정)
- 리스크: 변수 미설정 시 배포가 꺼지는 기본값이라, EKS를 다시 켤 때 변수 설정을 잊으면 배포가 안 됨 → 대응: 워크플로 주석 + `docs/design-notes.md`/PR 본문에 명시
- 리스크: `create-pr`(dev → main 릴리스 PR 자동 생성)도 함께 꺼짐 → 대응: 원래 `deploy`에 의존하던 job이라 동작 변화 없음

## 의존성
- GitHub Actions 저장소 변수(`vars.DEPLOY_ENABLED`) — 미설정 시 배포 꺼짐
- 로컬 검증: Docker (serverB 테스트 컨텍스트)
