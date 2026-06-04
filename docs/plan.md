# 계획서: Helm 차트 전면 재구성 (promotion 프로젝트)

---

# 계획서: logback 환경별 분기 (local 파일 / k8s stdout)

- 작성일: 2026-06-04

## 목표
6개 서비스의 logback-spring.xml에 Spring 프로필 분기를 추가해
docker-compose(local)에서는 파일 JSON 로그, EKS(k8s)에서는 stdout JSON 로그를 출력한다.

## 현재 상태
- 6개 서비스 모두 동일한 구조의 logback-spring.xml 보유
- CONSOLE(plain text) + FILE_JSON(LogstashEncoder) 항상 동시 출력 — 프로필 분기 없음
- docker-compose.yml에 SPRING_PROFILES_ACTIVE 없음

## 성공 기준
- [ ] 6개 logback-spring.xml에 `local` / `k8s` springProfile 블록 분리
- [ ] docker-compose.yml 6개 서비스에 `SPRING_PROFILES_ACTIVE: local` 추가
- [ ] helm promotion-app 6개 Deployment에 `SPRING_PROFILES_ACTIVE: k8s` env 추가
- [ ] `.\gradlew.bat :serverA:build :serverB:build :serverC:build -x test` 통과

## 비범위
- docker-compose.infra.yml, docker-compose.monitoring.yml, docker-compose.msa.yml 변경 없음
- logback 패턴/포맷 자체 변경 없음

## 단계별 작업 계획

### 단계 1: logback-spring.xml 6개 수정
- 변경 파일: 6개 서비스 `src/main/resources/logback-spring.xml`
- 기존 appender 정의는 그대로 유지, `<springProfile name="k8s">`와 `<springProfile name="local">` 블록으로 root logger 분리
  - `k8s`: STDOUT_JSON (LogstashEncoder, stdout)
  - `local`: CONSOLE(plain text) + ASYNC_FILE (기존 JSON 파일)
- 6개 파일 구조 동일 → defaultValue만 다르므로 병렬 작성 가능
- 검증: `.\gradlew.bat :serverA:build -x test`
- 예상 소요: 짧음

### 단계 2: docker-compose.yml SPRING_PROFILES_ACTIVE 추가
- 변경 파일: `docker-compose.yml` (6개 서비스 environment 블록)
- `SPRING_PROFILES_ACTIVE: local` 추가
- 예상 소요: 짧음

### 단계 3: helm Deployment env + values 추가
- 변경 파일: `helm/promotion-app/values.yaml`, 6개 deployment.yaml
- values.yaml 각 서비스에 `springProfilesActive: "k8s"` 추가
- deployment.yaml 각각에 `SPRING_PROFILES_ACTIVE` env 추가
- 검증: `helm lint helm/promotion-app/`, `helm template` 렌더링 확인
- 예상 소요: 짧음

## 리스크
- 프로필 미설정 시(SPRING_PROFILES_ACTIVE 없음) logback이 아무 프로필도 매칭 안 해 로그 출력 없음
  → 대응: 기본 프로필 fallback 없이 배포하면 안 됨, values.yaml 기본값으로 방어

---

# 계획서: ALB Ingress + Gateway 토큰버킷 Rate Limiting

- 작성일: 2026-06-04

## 목표
ALB Ingress로 HTTPS를 termination하여 gateway-service로 라우팅하고,
Spring Cloud Gateway에 Redis 기반 IP별 토큰버킷 Rate Limiting을 라우트별 차등 적용한다.

## 성공 기준
- [ ] `helm lint helm/promotion-app/` 통과
- [ ] `helm template`에서 Ingress 리소스 렌더링 확인
- [ ] `.\gradlew.bat :gateway-service:build -x test` 통과
- [ ] gateway-service application.yml에 purchase 전용 라우트 + rate limiting 필터 존재
- [ ] `RateLimiterConfig.java` X-Forwarded-For 기반 KeyResolver 구현

## 비범위
- AWS Load Balancer Controller 설치 (클러스터 사전 설치 가정)
- ACM 인증서 생성 및 WAF WebACL 생성 (Helm 범위 밖, ARN만 주입)
- Redis 실제 동작 테스트 (통합 테스트 범위)
- Eureka → K8s DNS 전환 (별도 작업)

## 단계별 작업 계획

### 단계 1: gateway-service 코드 변경 (의존성 + KeyResolver + 라우트)
- 변경 파일:
  - `gateway-service/build.gradle` — `spring-boot-starter-data-redis-reactive` 추가
  - `gateway-service/src/main/java/.../config/RateLimiterConfig.java` — 신규, X-Forwarded-For KeyResolver
  - `gateway-service/src/main/resources/application.yml` — Redis config + purchase 전용 라우트 분리 + rate limiting 필터
- rate limit 수치:
  - `POST /api/v1/promotions/purchase`: replenishRate=2, burstCapacity=5
  - 나머지 serverA/B/C 라우트: replenishRate=20, burstCapacity=50
  - aiops `/webhook/**, /action/**`: Rate Limiting 미적용
- 검증: `.\gradlew.bat :gateway-service:build -x test`
- 예상 소요: 보통

### 단계 2: docker-compose.yml gateway Redis env 추가
- 변경 파일: `docker-compose.yml` — gateway-service에 `SPRING_DATA_REDIS_HOST: redis`, `SPRING_DATA_REDIS_PORT: 6379` 추가
- local에서는 serverA와 같은 redis 컨테이너 공유 (Rate Limiting 키는 `request_rate_limiter.*` prefix로 네임스페이스 분리됨)
- 예상 소요: 짧음

### 단계 3: Helm Ingress 템플릿 + values + gateway deployment 수정
- 변경 파일:
  - `helm/promotion-app/templates/ingress/ingress.yaml` — 신규 ALB Ingress 리소스
  - `helm/promotion-app/values.yaml` — ingress 섹션 추가, gateway.redis 섹션 추가
  - `helm/promotion-app/templates/gateway/deployment.yaml` — Redis env 추가
- ALB 어노테이션: internet-facing, ip target-type, HTTPS 443, ACM cert, WAF(optional)
- 검증: `helm lint`, `helm template | Select-String "Ingress|redis"`
- 예상 소요: 짧음

### 단계 4: 최종 빌드 + lint 종합 검증
- `.\gradlew.bat :gateway-service:build -x test`
- `helm lint helm/promotion-app/`
- `helm template`으로 Ingress + gateway env 렌더링 확인
- 예상 소요: 짧음

## 리스크
- **리스크 1**: Redis 미연결 시 gateway 기동 실패 → 대응: `spring.data.redis.host` 기본값 `localhost` 설정
- **리스크 2**: `RemoteAddress`가 null인 경우 NPE → 대응: null 체크 후 fallback IP("unknown") 반환
- **리스크 3**: Rate Limiting이 429 반환 시 클라이언트에 Retry-After 헤더 없음 → 현재 범위 밖, 추후 개선

## 의존성
- gateway Redis: docker-compose는 `redis:6379`, K8s는 `--set gateway.redis.host=<ELASTICACHE>` 주입
- AWS Load Balancer Controller: EKS 클러스터에 사전 설치 필요
- ACM 인증서: `--set ingress.certificateArn=arn:aws:acm:...`로 주입

---

# 계획서: Spring Cloud Kubernetes DiscoveryClient 적용

- 작성일: 2026-06-04

## 목표
local 프로필에서는 Eureka, k8s 프로필에서는 Spring Cloud Kubernetes DiscoveryClient를 사용해
Java 코드 수정 없이 두 환경에서 `lb://` 라우팅이 동작하도록 한다.
gateway-service만 K8s 디스커버리 의존성과 RBAC이 필요하고, 나머지 4개 서비스는 Eureka 비활성화만 추가한다.

## 핵심 발견: 서비스명 불일치
- Eureka 등록명: `serverA`, `serverB`, `serverC` (spring.application.name, camelCase)
- K8s Service 리소스명: `server-a`, `server-b`, `server-c` (kebab-lowercase)
- K8s는 대문자를 Service명으로 허용하지 않으므로 `lb://serverA`로 K8s 조회 시 실패
- 해결: gateway `application-k8s.yml`에서 라우트를 `lb://server-a` 형식으로 재정의 (A안)

## 성공 기준
- [ ] `.\gradlew.bat :gateway-service:build -x test` 통과
- [ ] `helm lint helm/promotion-app/` 통과
- [ ] 5개 `application-k8s.yml` 파일 존재 확인
- [ ] gateway `application-k8s.yml` 라우트가 `lb://server-a` 형식 사용
- [ ] `helm template`에서 discovery 리소스가 `discovery.enabled=false` 시 렌더링 안 됨 확인

## 비범위
- spring.application.name 변경 (B안 미채택)
- 실제 K8s 클러스터에서 discovery 동작 검증
- serverA/B/C 서비스 간 직접 HTTP 호출에 K8s discovery 적용 (현재 직접 URL 사용)

## 단계별 작업 계획

### 단계 1: application-k8s.yml 5개 신규 생성
- 변경 파일: serverA/B/C/aiops/gateway-service `src/main/resources/application-k8s.yml`
- serverA/B/C/aiops: `eureka.client.enabled: false` 단순 비활성화
- gateway-service: eureka 비활성화 + kubernetes discovery 활성화 + 전체 라우트 재정의(`lb://server-a` 형식)
- 검증: 파일 존재 확인
- 예상 소요: 짧음

### 단계 2: gateway-service build.gradle 의존성 추가
- 변경 파일: `gateway-service/build.gradle`
- `spring-cloud-starter-kubernetes-client-discoveryclient` 추가
- Spring Cloud BOM 2025.0.1에 포함돼 있으므로 버전 미명시
- 검증: `.\gradlew.bat :gateway-service:build -x test`
- 예상 소요: 짧음

### 단계 3: Helm RBAC + gateway deployment serviceAccountName
- 변경 파일:
  - `helm/promotion-app/templates/rbac/gateway-rbac.yaml` 신규 — ServiceAccount + Role + RoleBinding (네임스페이스 스코프)
  - `helm/promotion-app/templates/gateway/deployment.yaml` — serviceAccountName 추가
- Role 권한: services, endpoints, pods → get/list/watch
- 검증: `helm lint`, `helm template | Select-String "ServiceAccount|Role"`
- 예상 소요: 짧음

### 단계 4: Helm discovery 조건부 + values 업데이트
- 변경 파일:
  - `helm/promotion-app/templates/discovery/deployment.yaml` — `{{- if .Values.discovery.enabled }}` guard 추가
  - `helm/promotion-app/templates/discovery/service.yaml` — 동일 guard 추가
  - `helm/promotion-app/values.yaml` — `discovery.enabled: true` 추가
- 검증: `helm template --set discovery.enabled=false`로 discovery 리소스 없음 확인
- 예상 소요: 짧음

## 리스크 및 대응
- **리스크 1**: spring-cloud-kubernetes가 K8s API 서버 미연결 상태에서 gateway 기동 실패
  → 대응: `application-k8s.yml`에 `spring.cloud.kubernetes.config.enabled: false`(ConfigMap 연동 비활성) 추가
- **리스크 2**: K8s Service명과 lb:// 이름 불일치로 404
  → 대응: gateway `application-k8s.yml` 라우트에서 `server-a` 형식 명시적 사용
- **리스크 3**: discovery.enabled=false로 배포 시 Eureka 없는데 서비스들이 Eureka 연결 시도
  → 대응: k8s 프로필 사용 시 eureka.client.enabled=false가 적용되므로 문제없음

## 의존성
- Spring Cloud 2025.0.1 BOM에 `spring-cloud-starter-kubernetes-client-discoveryclient` 포함
- K8s 클러스터에 `promotion` 네임스페이스 존재 필요

- 작성일: 2026-06-04

## 목표
`helm/trip/`(타 프로젝트 템플릿)을 삭제하고, promotion 프로젝트의 전체 서비스를
app / infra / monitoring 3개 독립 Helm 차트로 재구성한다.
각 서비스는 별도 Deployment/StatefulSet/DaemonSet으로 분리 배포된다.

## 성공 기준
- [ ] `helm/trip/` 디렉토리 완전 삭제
- [ ] `helm lint helm/promotion-app/` 통과 (warning 없음)
- [ ] `helm lint helm/promotion-infra/` 통과
- [ ] `helm lint helm/promotion-monitoring/` 통과
- [ ] `helm template` 결과에서 각 서비스 Deployment/StatefulSet/Service 리소스 확인
- [ ] 모든 Deployment에 `app.kubernetes.io/*` 표준 레이블 존재

## 비범위 (Out of Scope)
- 실제 클러스터 배포 및 동작 검증 (helm lint/template까지)
- MySQL, Redis Helm 리소스 (AWS RDS, ElastiCache 사용 가정)
- Ingress 리소스 (추후 별도 작업)
- CI/CD 파이프라인 연동
- 앱 로깅 방식 변경 (파일 → stdout, K8s 표준)
- Grafana 대시보드 JSON ConfigMap (추후 별도 작업)
- Kubernetes Secret 리소스 분리 (현재는 --set으로 주입)

## 단계별 작업 계획

### 단계 1: helm/trip/ 삭제 + 3개 차트 스켈레톤 생성
- 삭제: `helm/trip/` (7개 파일)
- 생성: 각 차트별 Chart.yaml, values.yaml, _helpers.tpl (9개 파일)
  - `helm/promotion-app/`
  - `helm/promotion-infra/`
  - `helm/promotion-monitoring/`
- 검증: 디렉토리 구조 확인 + `helm lint` 기본 통과
- 예상 소요: 짧음

### 단계 2: promotion-infra 템플릿 작성
- 변경 파일: `helm/promotion-infra/templates/` 8개
  - `kafka-statefulset.yaml` — KRaft 단일 노드, PVC 10Gi
  - `kafka-headless-service.yaml` — StatefulSet 내부 DNS용
  - `kafka-service.yaml` — ClusterIP (앱 접속용)
  - `kafka-connect-deployment.yaml` — Debezium Connect
  - `kafka-connect-service.yaml`
  - `debezium-init-job.yaml` — 커넥터 등록 Job (restartPolicy: OnFailure)
  - `redpanda-deployment.yaml` — Kafka UI
  - `redpanda-service.yaml`
- 검증: `helm lint helm/promotion-infra/`, `helm template`로 StatefulSet PVC 확인
- 예상 소요: 보통

### 단계 3: promotion-app 템플릿 작성
- 변경 파일: `helm/promotion-app/templates/` 15개
  - discovery: deployment + service
  - gateway: deployment + service
  - server-a: deployment + service + hpa
  - server-b: deployment + service + hpa
  - server-c: deployment + service + hpa
  - aiops: deployment + service
- 공통 적용: preStop sleep 20s, liveness/readiness /actuator/health/liveness·readiness, 표준 레이블
- 검증: `helm lint helm/promotion-app/`, HPA 리소스 렌더링 확인
- 예상 소요: 보통

### 단계 4: promotion-monitoring 템플릿 작성
- 변경 파일: `helm/promotion-monitoring/templates/` ~28개
  - otel-collector: deployment + service + configmap
  - prometheus: deployment + service + configmap (prometheus.yml 포함)
  - grafana: deployment + service + configmap (datasources 포함)
  - alertmanager: deployment + service + configmap
  - tempo: deployment + service + configmap
  - loki: deployment + service
  - vector: **DaemonSet** + configmap (노드 /var/log/pods/ 읽기)
  - cadvisor: **DaemonSet** (privileged, hostPath 볼륨)
  - redis-exporter, redis-b-exporter, kafka-exporter, mysql-a-exporter, mysql-c-exporter: 각 deployment + service (10개)
- 검증: `helm lint helm/promotion-monitoring/`, DaemonSet 렌더링 확인
- 예상 소요: 김

### 단계 5: 최종 helm lint 종합 검증
- 3개 차트 모두 `helm lint --strict` 실행
- `helm template`으로 전체 리소스 수 확인
- checklist 최종 업데이트
- 예상 소요: 짧음

## 리스크 및 대응
- **리스크 1**: monitoring ConfigMap config 내용 누락 → 대응: 핵심 설정만 values.yaml에 멀티라인으로 포함, 상세 설정은 TODO 주석
- **리스크 2**: vector DaemonSet의 로그 경로 (파일→stdout 전환 필요) → 대응: Helm 템플릿은 K8s 표준 경로(/var/log/pods/) 기준으로 작성하고 비범위로 명시
- **리스크 3**: Kafka StatefulSet PVC StorageClass가 클러스터에 없을 수 있음 → 대응: values.yaml에 storageClass 파라미터 노출

## 의존성
- MySQL, Redis: AWS RDS/ElastiCache endpoint를 `--set`으로 주입
- Kafka Connect → Kafka StatefulSet (같은 promotion-infra 차트 내)
- promotion-app → promotion-infra Kafka 서비스명 (`kafka-service.promotion.svc.cluster.local`)
- promotion-monitoring 익스포터 → RDS/ElastiCache endpoint `--set`으로 주입
