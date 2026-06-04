# 체크리스트: Helm 차트 전면 재구성 (promotion 프로젝트)

- 마지막 업데이트: 2026-06-04

## 진행 상황

- [x] 단계 1: helm/trip/ 삭제 + 3개 차트 스켈레톤 생성
  - [x] helm/trip/ 삭제 확인
  - [x] helm/promotion-app/ Chart.yaml, values.yaml, _helpers.tpl 생성
  - [x] helm/promotion-infra/ Chart.yaml, values.yaml, _helpers.tpl 생성
  - [x] helm/promotion-monitoring/ Chart.yaml, values.yaml, _helpers.tpl 생성
  - [x] helm lint 기본 통과 (3개 차트) — 0 failed

- [x] 단계 2: promotion-infra 템플릿 작성
  - [x] kafka-statefulset.yaml (KRaft, PVC 10Gi)
  - [x] kafka-headless-service.yaml
  - [x] kafka-service.yaml (ClusterIP)
  - [x] kafka-connect-deployment.yaml
  - [x] kafka-connect-service.yaml
  - [x] debezium-init-job.yaml (+ ConfigMap)
  - [x] redpanda-deployment.yaml
  - [x] redpanda-service.yaml
  - [x] helm lint helm/promotion-infra/ 통과 — 0 failed

- [x] 단계 3: promotion-app 템플릿 작성
  - [x] discovery-deployment.yaml + service
  - [x] gateway-deployment.yaml + service
  - [x] server-a-deployment.yaml + service + hpa
  - [x] server-b-deployment.yaml + service + hpa
    - [x] server-c-deployment.yaml + service + hpa
  - [x] aiops-deployment.yaml + service
  - [x] helm lint helm/promotion-app/ 통과 — 0 failed

- [x] 단계 4: promotion-monitoring 템플릿 작성
  - [x] otel-collector deployment + service + configmap
  - [x] prometheus deployment + service + configmap (alert-rules, recording-rules 포함)
  - [x] grafana deployment + service + configmap (datasource, dashboard)
  - [x] alertmanager deployment + service + configmap
  - [x] tempo deployment + service + configmap
  - [x] loki deployment + service
  - [x] vector DaemonSet + configmap + RBAC (ServiceAccount, ClusterRole, ClusterRoleBinding)
  - [x] cadvisor DaemonSet + service
  - [x] redis/redis-b/kafka/mysql-a/mysql-c exporter deployment + service (exporters.yaml)
  - [x] helm lint helm/promotion-monitoring/ 통과 — 0 failed

- [x] 단계 5: 최종 검증
  - [x] helm lint --strict (3개 차트 모두) — 0 failed
  - [x] helm template 렌더링: app(6D+3HPA+6S), infra(1SS+2D+1Job+4S+1CM), monitoring(11D+2DS+12S+6CM+RBAC)
  - [ ] git diff --stat으로 변경 범위 확인

## 최종 검증
- [x] 모든 Deployment에 app.kubernetes.io/* 표준 레이블 존재
- [x] HPA가 server-a, server-b, server-c에만 존재
- [x] Kafka StatefulSet에 volumeClaimTemplates 존재
- [x] debezium-init이 Job 리소스 (Deployment 아님)
- [x] vector, cadvisor가 DaemonSet 리소스
- [x] values.yaml 민감 정보가 빈 문자열 (실제 값 노출 없음)
- [x] 비범위(MySQL/Redis Helm 리소스, Ingress) 침범하지 않음

---

# 체크리스트: logback 환경별 분기

- 마지막 업데이트: 2026-06-04

## 진행 상황
- [x] 단계 1: logback-spring.xml 6개 수정
  - [x] serverA
  - [x] serverB (queueSize 2048 → 512 주의: 원본은 512였음)
  - [x] serverC (queueSize 2048 보존)
  - [x] aiops (kafka logger 없음 보존)
  - [x] gateway-service
  - [x] discovery-service
  - [x] `.\gradlew.bat :serverA:build :serverB:build :serverC:build -x test` 통과
- [x] 단계 2: docker-compose.yml SPRING_PROFILES_ACTIVE 추가 (6개 서비스)
- [x] 단계 3: helm Deployment env + values 추가
  - [x] helm/promotion-app/values.yaml springProfilesActive: "k8s" 추가
  - [x] 6개 deployment.yaml SPRING_PROFILES_ACTIVE env 추가
  - [x] helm lint 통과 — 6개 Deployment 렌더링 확인

---

# 체크리스트: ALB Ingress + Gateway 토큰버킷 Rate Limiting

- 마지막 업데이트: 2026-06-04

## 진행 상황
- [x] 단계 1: gateway-service 코드 변경
  - [x] build.gradle redis-reactive 의존성 추가
  - [x] RateLimiterConfig.java KeyResolver 신규 생성
  - [x] application.yml Redis config + purchase 라우트 분리 + rate limiting 필터
  - [x] `.\gradlew.bat :gateway-service:build -x test` 통과
- [x] 단계 2: docker-compose.yml gateway Redis env 추가
- [x] 단계 3: Helm 변경
  - [x] helm/promotion-app/templates/ingress/ingress.yaml 신규
  - [x] values.yaml ingress + gateway.redis 섹션 추가
  - [x] gateway/deployment.yaml Redis env 추가
  - [x] helm lint 통과 — 0 failed
- [x] 단계 4: 최종 검증 — Ingress 1개 + Deployment 6개 + HPA 3개 + Service 6개 렌더링 확인

---

# 체크리스트: Spring Cloud Kubernetes DiscoveryClient 적용

- 마지막 업데이트: 2026-06-04

## 진행 상황
- [x] 단계 1: application-k8s.yml 5개 신규 생성
  - [x] serverA/B/C/aiops — eureka.client.enabled: false
  - [x] gateway-service — eureka 비활성 + http://server-a:PORT 직접 URI 라우트 (K8s discovery 불필요)
- [x] 단계 2: Helm discovery 조건부 + values
  - [x] discovery/deployment.yaml enabled guard
  - [x] discovery/service.yaml enabled guard
  - [x] values.yaml discovery.enabled: true 추가
  - [x] helm lint 통과, `--set discovery.enabled=false` 시 Deployment/Service 각 1개 감소 확인
- 단계 변경: kubernetes-client-discoveryclient 의존성 추가 및 RBAC 불필요 (직접 URI 방식 채택)

## 발견 사항
- 기존 trip 템플릿의 SPRING_REDIS_HOST는 Spring Boot 2.x 방식. promotion 차트에서는 SPRING_DATA_REDIS_HOST (Spring Boot 3.x) 사용
- vector DaemonSet: 앱이 현재 파일로 로그를 쓰므로 K8s 전환 시 logback stdout 설정 변경 필요 (별도 작업)
- cAdvisor: EKS에서는 kubelet /metrics/cadvisor로 대체 가능. 포트폴리오 목적으로 DaemonSet 포함
