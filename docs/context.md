# 맥락 노트: Helm 로컬 values DB 이름 수정 + 죽은 Eureka 설정 제거 (BL-010, BL-012)

## 왜 이 방식을 선택했는가
- BL-010: PR #22에서 serverA DB 이름을 `order`로 바꿨는데 `values-local.yaml`만 `promotion`으로 남았다. docker-compose MySQL(3307)의 `MYSQL_DATABASE`도 `order`.
- BL-012: K8s에는 discovery-service(Eureka)를 배포하지 않고, 서비스 조회는 `application-k8s.yml`의 SimpleDiscoveryClient 정적 등록으로 한다(PR #21). 7개 모듈은 k8s 프로필에서 `eureka.client.enabled: false`라 `EUREKA_DEFAULT_ZONE` env가 읽히지 않는다.
- `values.yaml`의 `discovery:` 섹션도 대응 템플릿이 없고 참조 0건 → 같은 성격이라 함께 삭제 (사용자 결정).
- 검증은 kind 실배포 대신 `helm template` 전후 diff (사용자 결정): env 삭제·URL 한 단어 수정이라 렌더링 결과가 의도대로면 충분.

## 검토했으나 채택하지 않은 대안
### 대안 A: codebot k8s 프로필까지 이번 PR에서 정리
- 무엇: codebot은 `application-k8s.yml`이 없어 K8s에서도 Eureka 클라이언트가 켜져 있고 이 env를 실제로 읽는다. `application-k8s.yml` + `K8sDiscoveryConfigTest` 추가.
- 왜 안 썼나: Java 모듈 변경이라 Helm 정리 PR과 성격이 다름. env를 지워도 등록 대상이 `discovery-service:8761` → `localhost:8761`로 바뀔 뿐 둘 다 실패(로그만)이고, aiops는 정적 등록으로 codebot을 찾으므로 동작 차이 없음 → 백로그로 분리.

### 대안 B: 템플릿 env만 삭제, values 키는 유지
- 왜 안 썼나: 참조 없는 고아 키가 남아 다음 사람이 의미를 오해한다.

## 기존 코드베이스 컨벤션
- 서비스별 템플릿: `helm/promotion-app/templates/{서비스}/deployment.yaml` (+ `deployment-canary.yaml`)
- env는 `- name: X` / `value: {{ .Values.<svc>.<key> | quote }}` 2줄 형식

## 관련 파일/위치
- `values-local.yaml` (루트) — 로컬 kind 오버라이드
- `helm/promotion-app/values.yaml` — 기본 values
- `helm/promotion-app/templates/*/deployment*.yaml` — EUREKA env 11곳
- `docs/design-notes.md` "K8s DiscoveryClient" 항목 — Eureka 미배포 결정 배경
