# 맥락 노트: Helm 차트 전면 재구성 (promotion 프로젝트)

## 왜 이 방식을 선택했는가

기존 `helm/trip/`은 택시/여행 도메인의 다른 프로젝트에서 가져온 템플릿으로,
chart name / helper prefix / values 모두 promotion 프로젝트와 무관했다.

3개 분리 차트 구조(app / infra / monitoring)를 선택한 이유:
- 관심사 분리: 모니터링 재배포가 앱 서비스에 영향을 주지 않음
- 인프라(Kafka)는 앱보다 훨씬 느리게 변경됨 → 독립 릴리즈 사이클 필요
- 실무 패턴: 대부분의 사내 쿠버네티스 환경이 infra/app/monitoring을 별도 릴리즈로 관리

## 핵심 설계 결정

### MySQL, Redis → AWS 관리형 서비스, Helm 제외
- RDS, ElastiCache를 사용하므로 Helm에서 MySQL/Redis StatefulSet 불필요
- DB endpoint는 `helm upgrade --set serverA.datasource.url=...` 로 주입

### Kafka → StatefulSet + PVC
- 상태를 가지는 서비스이므로 Deployment 대신 StatefulSet 사용
- KRaft 모드 단일 노드 (docker-compose와 동일 설정)
- StorageClass: gp2 (AWS EKS 기본값), values.yaml에서 오버라이드 가능
- PVC 10Gi (docker-compose kafka-data volume 대응)

### debezium-init → Kubernetes Job
- Docker Compose의 `debezium-init` 컨테이너(curl로 커넥터 등록 후 종료)는
  K8s Job으로 정확히 대응됨 (restartPolicy: OnFailure)
- 커넥터 JSON 설정은 ConfigMap으로 마운트

### vector, cAdvisor → DaemonSet
- cAdvisor: 노드별 컨테이너 메트릭 수집. DaemonSet이 표준 K8s 배포 방식
- vector: Docker Compose에서는 shared-logs 볼륨으로 파일 읽기.
  K8s에서는 앱이 stdout에 로그를 쓰면 kubelet이 `/var/log/pods/`에 저장.
  vector DaemonSet이 이 경로를 hostPath로 마운트하여 읽는 것이 K8s 표준 패턴.
  **주의**: 현재 앱들은 파일에 로그를 씀(shared-logs 볼륨). K8s 전환 시
  logback 설정을 stdout으로 변경하는 작업이 별도로 필요함 (이번 범위 밖).

### Secrets 처리
- 포트폴리오 목적으로 values.yaml에 빈 문자열로 정의
- 실제 배포 시: `helm upgrade ... --set serverA.datasource.password=<value>`
- 프로덕션이라면 External Secrets Operator 또는 AWS Secrets Manager 연동 권장

### _helpers.tpl prefix
- 기존 `advertisechart` → `promotion`으로 통일
- 각 차트별 prefix: `promotion-app`, `promotion-infra`, `promotion-monitoring`

## 검토했으나 채택하지 않은 대안

### 대안 A: 단일 차트 `helm/promotion/`
- 무엇: 모든 서비스를 하나의 Chart에 담음
- 왜 안 썼나: values.yaml이 매우 비대해지고, 모니터링 재배포가 앱 롤아웃을 트리거함.
  사용자가 Q1에서 분리 차트(B안)를 선택.

### 대안 B: Umbrella Chart (app+infra+monitoring을 subcharts로)
- 무엇: `helm/promotion/` 하나에 charts/ 디렉토리로 서브차트 구성
- 왜 안 썼나: 구조가 복잡하고 서브차트 의존성 관리 부담. 포트폴리오 목적에 과잉.

### 대안 C: Community Helm Charts 활용 (bitnami/kafka 등)
- 무엇: Kafka, Prometheus 등을 직접 작성하지 않고 공개 차트 의존성으로 추가
- 왜 안 썼나: 사용자가 "monitoring도 직접 추가하는 게 좋지 않냐"고 하여 직접 작성 선택.
  직접 작성하면 기존 docker-compose 설정과 1:1 대응이 명확함.

### 대안 D: MySQL/Redis를 Helm StatefulSet으로
- 무엇: RDS/ElastiCache 대신 K8s StatefulSet으로 MySQL, Redis 배포
- 왜 안 썼나: 사용자가 Q3에서 AWS 관리형 사용을 명시.

## 서비스 → 차트 매핑

| 서비스 | 차트 | 리소스 타입 |
|--------|------|------------|
| discovery-service | promotion-app | Deployment |
| gateway-service | promotion-app | Deployment |
| server-a | promotion-app | Deployment + HPA |
| server-b | promotion-app | Deployment + HPA |
| server-c | promotion-app | Deployment + HPA |
| aiops | promotion-app | Deployment |
| kafka | promotion-infra | StatefulSet + PVC |
| kafka-connect | promotion-infra | Deployment |
| debezium-init | promotion-infra | Job |
| redpanda-console | promotion-infra | Deployment |
| otel-collector | promotion-monitoring | Deployment |
| prometheus | promotion-monitoring | Deployment + ConfigMap |
| grafana | promotion-monitoring | Deployment + ConfigMap |
| alertmanager | promotion-monitoring | Deployment + ConfigMap |
| tempo | promotion-monitoring | Deployment + ConfigMap |
| loki | promotion-monitoring | Deployment |
| vector | promotion-monitoring | DaemonSet + ConfigMap |
| cadvisor | promotion-monitoring | DaemonSet |
| redis/redis-b-exporter | promotion-monitoring | Deployment |
| kafka-exporter | promotion-monitoring | Deployment |
| mysql-a/c-exporter | promotion-monitoring | Deployment |

## 환경변수 네이밍 (도커 컴포즈 → K8s 동일 유지)
- `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`
- `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT` (Spring Boot 3.x 표준)
- `SPRING_KAFKA_BOOTSTRAP_SERVERS`
- `EUREKA_DEFAULT_ZONE`
- `MANAGEMENT_OTLP_TRACING_ENDPOINT`

---

# 맥락 노트: logback 환경별 분기

## 왜 이 방식을 선택했는가
docker-compose(local)에서는 vector가 `/logs/*.log` 파일을 읽고,
EKS(k8s)에서는 vector DaemonSet이 kubelet이 저장하는 `/var/log/pods/`를 읽는다.
두 방식 모두 LogstashEncoder JSON 포맷을 사용하므로 vector 파싱 설정 변경 없이 동작한다.
Spring `<springProfile>` 분기로 SPRING_PROFILES_ACTIVE 환경변수만 주입하면 된다.

## 현재 logback 구조 (수정 전)
- CONSOLE (plain text pattern) + ASYNC_FILE (LogstashEncoder JSON) 항상 동시 출력
- 프로필 분기 없음 — k8s에서도 파일에 쓰려 하지만 `/logs` 볼륨이 없어 에러 발생 가능

## 수정 후 구조
- `local` 프로필: CONSOLE(plain text) + ASYNC_FILE(JSON → /logs/{APP_NAME}.log)
- `k8s` 프로필: STDOUT_JSON(JSON → stdout, kubelet이 /var/log/pods/에 저장)

## 프로필 미설정 리스크
springProfile 블록 안에만 root logger가 있으므로,
SPRING_PROFILES_ACTIVE를 설정하지 않으면 로그가 전혀 안 나온다.
→ helm values.yaml defaultValue: "k8s", docker-compose에 명시적으로 local 설정 필수

---

# 맥락 노트: ALB Ingress + Gateway 토큰버킷 Rate Limiting

## 왜 이 방식을 선택했는가

**ALB Ingress**: AWS EKS에서 ALB는 ACM 인증서 자동 연동, WAF 어노테이션 한 줄 연결이 가능해
HTTPS termination을 코드 변경 없이 처리할 수 있다. NGINX Ingress 대비 운영 부담이 낮다.

**토큰버킷 Rate Limiting 위치**: AWS Load Balancer Controller(ALB Ingress)는 rate limiting을
지원하지 않으므로 Spring Cloud Gateway에서 처리한다.

**Gateway 전용 Redis**: serverA Redis와 분리해 Rate Limiting 키 공간을 격리한다.
docker-compose 로컬에서는 같은 redis 컨테이너를 공유해도 키 prefix가 달라 충돌 없음.

**X-Forwarded-For**: ALB는 클라이언트 IP를 `X-Forwarded-For` 헤더에 삽입한다.
`getRemoteAddress()`는 ALB 내부 IP를 반환하므로 반드시 헤더 기반으로 추출해야 한다.

**라우트별 차등 제한**: 선착순 구매(`POST /api/v1/promotions/purchase`)는
어뷰저 방어를 위해 초당 2건/버스트 5건으로 엄격하게 제한.
조회 API는 UX 저하 없이 초당 20건/버스트 50건으로 여유 있게 설정.

**aiops 미적용**: `/webhook/**, /action/**`는 Alertmanager 내부 호출이므로
Rate Limiting 제외. 외부 노출 경로가 아님.

## 검토했으나 채택하지 않은 대안

### NGINX Ingress + ingress-level rate limiting
- 무엇: NGINX Ingress Controller 설치 후 어노테이션으로 rate limit
- 왜 안 썼나: AWS ALB 통합(ACM, WAF)이 깔끔하지 않고, NGINX 파드 운영 부담 추가.
  EKS 환경이므로 ALB가 더 자연스러운 선택.

### serverA Redis 공유
- 무엇: gateway의 rate limiting 키를 serverA Redis에 함께 저장
- 왜 안 썼나: 사용자가 전용 Redis 선택. 앱 캐시와 rate limiting 트래픽을 섞지 않는 것이 올바른 분리.

---

# 맥락 노트: Spring Cloud Kubernetes DiscoveryClient 적용

## 왜 이 방식을 선택했는가
EKS에서 Eureka 서버 파드를 운영하는 것은 불필요한 리소스 낭비다.
K8s는 CoreDNS + Service 리소스로 서비스 디스커버리를 네이티브로 제공하므로,
`spring-cloud-kubernetes-client-discoveryclient`를 통해 `lb://` 라우팅을 그대로 유지하면서
Eureka 없이 동작하게 한다. Java 코드 수정 없음.

## 왜 A안(gateway 라우트 재정의)을 선택했는가
K8s Service 이름 규칙(소문자+하이픈)과 Eureka 등록명(camelCase)이 달라
`lb://serverA`로 K8s를 조회하면 `server-a` Service를 찾을 수 없다.
B안(spring.application.name 전체 변경)은 5개 서비스 application.yml + Eureka 등록명 + 라우트를
모두 바꿔야 하는 대규모 변경이다. A안은 gateway `application-k8s.yml`에서만 라우트를 재정의하므로
변경 범위가 최소화된다.

## RBAC이 gateway-service에만 필요한 이유
spring-cloud-kubernetes DiscoveryClient는 K8s API 서버를 호출해 Services/Endpoints를 조회한다.
이 조회 권한이 없으면 서비스를 찾을 수 없다. 반면 serverA/B/C/aiops는 조회하는 쪽이 아니라
"조회 대상"이므로 RBAC 불필요.

## 검토했으나 채택하지 않은 대안

### 대안: spring.application.name 전체 kebab-case 변경 (B안)
- 무엇: serverA → server-a로 spring.application.name 변경, gateway 라우트 통일
- 왜 안 썼나: 5개 서비스 application.yml + 전체 라우트 변경 필요. 변경 범위 과대.

### 대안: ClusterRole 사용
- 무엇: Role 대신 ClusterRole로 전 네임스페이스 서비스 조회
- 왜 안 썼나: 최소 권한 원칙. `promotion` 네임스페이스 내 서비스만 조회하면 충분하므로 Role + RoleBinding이 올바른 선택.

## 관련 파일
- `gateway-service/build.gradle` — redis-reactive 의존성
- `gateway-service/src/main/java/.../config/RateLimiterConfig.java` — KeyResolver 빈
- `gateway-service/src/main/resources/application.yml` — Redis config, 라우트 rate limiting
- `helm/promotion-app/templates/ingress/ingress.yaml` — ALB Ingress
- `helm/promotion-app/values.yaml` — ingress.*, gateway.redis.*

## 관련 파일
- `docker-compose.yml` — 전체 서비스 env/port 원본
- `docker-compose.infra.yml` — 인프라 서비스 원본
- `docker-compose.monitoring.yml` — 모니터링 서비스 원본
- `monitoring/` — Prometheus/Grafana/OTel 설정 파일 원본 (ConfigMap 내용 참조)
- `debezium/` — 커넥터 JSON (Job ConfigMap 내용 참조)
