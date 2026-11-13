# 계획서: Helm 로컬 values DB 이름 수정 + 죽은 Eureka 설정 제거 (백로그 BL-010, BL-012)

- 작성일: 2026-11-13
- 관련 이슈/티켓: 백로그 BL-010, BL-012

## 목표
로컬 kind용 `values-local.yaml`의 serverA DB 이름을 `order`로 고치고, K8s에 존재하지 않는 Eureka 관련 Helm 설정(`EUREKA_DEFAULT_ZONE` env, `eurekaDefaultZone` 값, `discovery:` 섹션)을 제거한다.

## 성공 기준
- [ ] `values-local.yaml` serverA JDBC URL의 DB 이름이 `order` (`helm template -f values-local.yaml` 렌더링 결과에서 확인)
- [ ] `helm/` 아래 `EUREKA_DEFAULT_ZONE`·`eurekaDefaultZone`·`Values.discovery` 참조 0건 (grep)
- [ ] `helm template` 두 조합(기본 values / + values-local) 렌더링 성공, `helm lint` 통과
- [ ] 변경 전후 렌더링 diff에서 EUREKA env 줄(11개 Deployment)과 serverA DB 이름 외 차이 없음

## 비범위 (Out of Scope)
- codebot `application-k8s.yml` 추가(K8s에서 Eureka 클라이언트 비활성화) → 백로그 신규 항목으로 분리
- 애플리케이션 yml의 `${EUREKA_DEFAULT_ZONE:...}` 기본값, docker-compose의 `EUREKA_DEFAULT_ZONE` (local Eureka용, 유지)
- 실제 kind 배포 검증 (렌더링 diff로 대체 — 사용자 결정)

## 단계별 작업 계획

### 단계 1: 변경 전 렌더링 기준선 저장
- 변경 파일: 없음 (scratchpad에 렌더링 결과 저장)
- 변경 내용 요약: `helm template`을 기본 values / + `values-local.yaml` 두 조합으로 렌더링해 before 파일로 저장.
- 검증 방법: 두 렌더링 명령 exit 0
- 롤백 방법: 해당 없음
- 예상 소요: 짧음

### 단계 2: BL-010 — values-local.yaml DB 이름 수정
- 변경 파일: `values-local.yaml`
- 변경 내용 요약: serverA datasource URL `/promotion?` → `/order?`.
- 검증 방법: 단계 4 diff
- 롤백 방법: `git checkout -- values-local.yaml`
- 예상 소요: 짧음

### 단계 3: BL-012 — 죽은 Eureka 설정 제거
- 변경 파일: `helm/promotion-app/templates/{gateway,server-a(+canary),server-b(+canary),server-c(+canary),user-service,aiops,codebot,cs-bot}/deployment*.yaml` 11개, `helm/promotion-app/values.yaml`
- 변경 내용 요약: 템플릿의 `EUREKA_DEFAULT_ZONE` env 2줄씩 삭제, values의 `eurekaDefaultZone` 키 8개와 `discovery:` 섹션 삭제.
- 검증 방법: `helm/` grep 0건
- 롤백 방법: `git checkout -- helm/`
- 예상 소요: 짧음

### 단계 4: 렌더링 diff 검증
- 변경 파일: 없음
- 변경 내용 요약: after 렌더링 후 before와 diff, `helm lint` 실행.
- 검증 방법: diff가 EUREKA env 22줄 삭제(기본 values 기준) + values-local의 DB 이름 2줄(serverA deployment·canary)만 포함 (`rollme` 무작위 annotation은 제외)
- 롤백 방법: 해당 없음
- 예상 소요: 짧음

### 단계 5: 문서 갱신
- 변경 파일: `docs/backlog.md`(BL-010·012 진행 중→완료, codebot 항목 신규), `docs/checklist.md`
- 검증 방법: 육안 확인
- 예상 소요: 짧음

## 리스크 및 대응
- 리스크: 렌더링에 필수 값(image 등)이 비어 실패 → 대응: before/after 동일한 `--set` 인자로 렌더링 (비교가 목적)
- 리스크: canary 템플릿이 조건부라 기본 렌더링에 안 나옴 → 대응: canary 활성화 값으로 추가 렌더링해 diff
- 리스크: values-local.yaml이 다른 환경에서 `promotion` DB를 의도했을 가능성 → docker-compose(`MYSQL_DATABASE: order`, 3307)와 serverA 기본 URL(`/order`)로 확인 완료

## 의존성
- helm v3.17.0 (로컬 설치 확인)
