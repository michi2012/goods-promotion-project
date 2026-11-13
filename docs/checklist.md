# 체크리스트: serverA 계약 테스트 동작시키기

- 마지막 업데이트: 2026-11-13

## [완료] OpenApiContractTest 복구

### 진행 상황
- [x] 단계 1: `springdoc-openapi-starter-webmvc-api:2.8.5` 추가 (승인 완료)
  - [x] 검증 통과 (컴파일 + `/v3/api-docs` 응답으로 계약 테스트 실행됨)
- [x] 단계 2: `OpenApiContractTest`가 `AbstractSpringBootTest` 상속
  - [x] 계획 대비 추가 (사용자 승인): springdoc이 produces 미지정 응답을 `*/*`로 기술 → `application.yaml`에 `springdoc.default-produces-media-type: application/json`
  - [x] 검증 통과 (`--tests OpenApiContractTest` 2/2)
  - [x] CI 환경변수(H2 URL) 설정 상태에서도 2/2 통과
- [x] 단계 3: serverA 전체 회귀 + arch-snapshot
  - [x] 검증 통과 (`gradlew :serverA:test` 82/82, 실패 0)

### 최종 검증
- [x] serverA 전체 테스트 통과 (82/82)
- [x] 신규 의존성은 승인된 1개뿐 (`springdoc-openapi-starter-webmvc-api:2.8.5`)
- [x] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인 (application.yaml 1줄은 사용자 승인)
- [x] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인

## 이후 작업
- [ ] 전체 E2E(화면 + 서버 간 흐름) 재확인 → PR

## 발견 사항 (작업 중 별도 처리 필요한 것)
- CI 워크플로의 H2 환경변수는 H2 의존성이 없어 사실상 미사용 (비범위)
- serverB `OpenApiContractTest`도 같은 구조(produces 미지정 + `application/json` 조회)라 같은 `*/*` 문제가 있을 가능성 — serverB 컨테이너 미기동으로 미확인 (비범위)
