---
name: k8s
description: Kubernetes, Helm, kubectl, HPA, RBAC, values.yaml, Deployment, Ingress, ServiceAccount 작업 시 활성화. 사용자가 helm, kubectl, k8s, kubernetes, HPA, deployment, values.yaml, ingress, RBAC, ServiceAccount, 네임스페이스, 파드, 스케일링 등을 언급하거나 helm/ 폴더 작업 시 발동.
---

# Kubernetes / Helm 규칙

## 0. 작업 시작 전

새 리소스 추가 또는 기존 리소스 수정 전 반드시 다음을 먼저 읽는다:
- `helm/promotion-app/values.yaml` — values 키 패턴 확인
- 같은 종류의 기존 템플릿 1개 (예: HPA 추가라면 `server-a/hpa.yaml` 먼저 읽기)

기존 패턴을 **그대로 따른다**. 새 패턴을 도입하지 마라.

---

## 1. 이 프로젝트 Helm 차트 구조

```
helm/
  promotion-app/          # 앱 서비스 (server-a/b/c, aiops, gateway, ingress)
  promotion-infra/        # Kafka StatefulSet, Kafka Connect, Debezium Job
  promotion-monitoring/   # Prometheus, Grafana, Tempo, Loki, Vector, exporters
```

### 네임스페이스

| 차트 | 네임스페이스 |
|------|-------------|
| promotion-app | `promotion` |
| promotion-infra | `promotion` |
| promotion-monitoring | `promotion-monitoring` |

### values.yaml 서비스 키 패턴

```yaml
# camelCase 사용 (K8s 리소스명과 다름에 주의)
serverA:   # → K8s 리소스명: server-a
serverB:   # → K8s 리소스명: server-b
serverC:   # → K8s 리소스명: server-c
gateway:   # → K8s 리소스명: gateway-service
aiops:     # → K8s 리소스명: aiops
```

### 서비스 간 DNS (K8s 환경)

`SPRING_PROFILES_ACTIVE=k8s`에서는 Eureka 없이 K8s Service DNS 직접 사용:

| 서비스 | DNS |
|--------|-----|
| server-a | `http://server-a:8080` |
| server-b | `http://server-b:8081` |
| server-c | `http://server-c:8082` |
| aiops | `http://aiops:8085` |
| gateway | `http://gateway-service:8088` |

---

## 2. 레이블 규칙

모든 K8s 리소스에 아래 레이블을 일관되게 붙인다.

```yaml
labels:
  app.kubernetes.io/name: {{ .Values.serverA.name }}
  app.kubernetes.io/instance: {{ .Release.Name }}
  app.kubernetes.io/managed-by: {{ .Release.Service }}
```

**selector 레이블**은 `app.kubernetes.io/name`만 사용:

```yaml
selector:
  matchLabels:
    app.kubernetes.io/name: {{ .Values.serverA.name }}
```

> `app.kubernetes.io/name`은 kube-state-metrics가 HPA·파드 알람에서 서비스를 식별하는 데 사용된다. 누락 시 Prometheus 알람 라벨 조회 실패.

---

## 3. HPA 작성 규칙

### 표준 HPA 템플릿

```yaml
{{- if .Values.serverX.autoscaling.enabled }}
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {{ .Values.serverX.name }}-hpa
  namespace: {{ .Values.namespace }}
  labels:
    app.kubernetes.io/name: {{ .Values.serverX.name }}
    app.kubernetes.io/instance: {{ .Release.Name }}
    app.kubernetes.io/managed-by: {{ .Release.Service }}
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: {{ .Values.serverX.name }}
  minReplicas: {{ .Values.serverX.autoscaling.minReplicas }}
  maxReplicas: {{ .Values.serverX.autoscaling.maxReplicas }}
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: {{ .Values.serverX.autoscaling.targetCPUUtilizationPercentage }}
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 60
    scaleDown:
      stabilizationWindowSeconds: 300   # 스케일 다운은 5분 안정화 후 (플래핑 방지)
{{- end }}
```

### values.yaml autoscaling 섹션 표준

```yaml
serverX:
  autoscaling:
    enabled: true
    minReplicas: 2
    maxReplicas: 5
    targetCPUUtilizationPercentage: 60
```

### ⚠️ HPA 작동 조건 — resources.requests 필수

HPA는 CPU 사용률을 `실제사용량 / requests.cpu`로 계산한다. `requests` 미설정 시 메트릭 수집이 불가하여 스케일링이 동작하지 않는다.

```yaml
resources:
  requests:
    cpu: "200m"     # HPA 계산 기준. 반드시 설정
    memory: "400Mi"
  limits:
    cpu: "1000m"
    memory: "768Mi"
```

---

## 4. RBAC / ServiceAccount

### 기본 패턴

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ .Values.serviceX.name }}
  namespace: {{ .Values.namespace }}
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: {{ .Values.serviceX.name }}-role
rules:
  - apiGroups: [""]
    resources: ["pods"]
    verbs: ["get", "list", "watch"]
  - apiGroups: ["apps"]
    resources: ["deployments"]
    verbs: ["get", "list", "watch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: {{ .Values.serviceX.name }}-role-binding
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: {{ .Values.serviceX.name }}-role
subjects:
  - kind: ServiceAccount
    name: {{ .Values.serviceX.name }}
    namespace: {{ .Values.namespace }}
```

### 최소 권한 원칙

| 권한 | 기준 |
|------|------|
| `get/list/watch` | 조회 목적이면 기본 부여 |
| `patch/update` | 변경이 필요한 리소스에만 명시적으로 추가 |
| `delete/create` | 사유 명시 후 추가. 기본 부여 금지 |

> 로컬(Docker Desktop)은 kubeconfig가 admin 권한이라 RBAC 누락이 드러나지 않는다. EKS 배포 전 ServiceAccount 권한을 반드시 검증할 것.

---

## 5. Deployment 작성 규칙

### imagePullPolicy

```yaml
imagePullPolicy: {{ .Values.imagePullPolicy }}
# values.yaml 기본값: Always (EKS 운영)
# 로컬 테스트: IfNotPresent
```

### 환경변수 패턴

```yaml
env:
  - name: SPRING_PROFILES_ACTIVE
    value: {{ .Values.serverA.springProfilesActive }}
  - name: SPRING_DATASOURCE_PASSWORD
    value: {{ .Values.serverA.datasource.password }}  # --set 으로 주입
```

### Health Probe

```yaml
readinessProbe:           # 트래픽 수신 준비 완료 판단
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10
  failureThreshold: 3
livenessProbe:            # 재시작 필요 여부 판단
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 60
  periodSeconds: 15
  failureThreshold: 5
```

> `readinessProbe` 없으면 파드 기동 직후 트래픽을 받아 503 발생. 반드시 설정.  
> `initialDelaySeconds`는 Spring Boot 기동 시간(약 20~30s) 이상으로 설정. 짧으면 시작 중 재시작 루프.

---

## 6. helm upgrade 규칙

### 변경 전 렌더링 검증

chart를 수정할 때마다 `helm template`으로 YAML 렌더링이 깨지지 않는지 확인한다:

```bash
helm template promotion-app ./helm/promotion-app -f ./helm/promotion-app/values.yaml
```

외부 차트 의존성(`Chart.yaml`의 `dependencies`)이 있는 경우, 로컬에서 처음 실행하기 전에 반드시:

```bash
helm dependency build ./helm/promotion-istio   # Chart.lock 기반 다운로드
```

> `helm dependency build` 없이 `helm template` 실행 시 `found in Chart.yaml, but missing in charts/` 오류로 실패한다.

### 클러스터 리소스 직접 수정 금지

```bash
# ❌ 금지 — Helm 상태와 불일치 발생, 다음 helm upgrade 시 덮어씌워짐
kubectl edit deployment server-a -n promotion

# ✅ 항상 Helm chart → helm upgrade 경로로만 변경
```

### helm upgrade 명령

```bash
helm upgrade promotion-app ./helm/promotion-app \
  -n promotion \
  --install \
  --wait \      # 모든 파드 Ready 대기
  --atomic \    # 실패 시 자동 롤백
  --timeout 5m \
  --set serverA.image=<ECR_URL>/server-a:tag \
  --set serverA.datasource.password=secret
```

> `--atomic` 없으면 배포 실패 시 파드가 CrashLoopBackOff 상태로 남아 수동 롤백이 필요하다.

### 민감값 주입

```bash
# ✅ --set 으로 주입
--set serverA.datasource.password=$DB_PASSWORD

# ❌ values.yaml에 직접 기입 금지 (git 노출 위험)
```

values.yaml의 민감값 키는 `""` 빈 문자열로 두고 `# --set` 주석을 단다.

### 롤백

```bash
helm rollback promotion-app -n promotion          # 직전 버전
helm rollback promotion-app 3 -n promotion        # 특정 revision
helm history promotion-app -n promotion           # 이력 확인
```

---

## 7. 로컬 vs EKS 차이점

| 항목 | 로컬 (Docker Desktop) | EKS |
|------|----------------------|-----|
| RBAC | kubeconfig admin으로 무시됨 | ServiceAccount 권한 그대로 적용 |
| imagePullPolicy | IfNotPresent | Always |
| DB/Redis | host.docker.internal | RDS/ElastiCache 엔드포인트 |
| Kafka | host.docker.internal:9092 | MSK 또는 Kafka StatefulSet |
| Ingress | 미동작 | ALB Ingress Controller |

---

## 8. 자가 점검

- [ ] 새 HPA 추가 시 해당 Deployment에 `resources.requests.cpu`가 설정되어 있는가?
- [ ] 모든 K8s 리소스에 `app.kubernetes.io/name` 레이블이 있는가?
- [ ] 민감값(password, token, apiKey)이 values.yaml에 직접 기입되지 않았는가?
- [ ] 새 ServiceAccount 권한이 최소 권한 원칙을 지키는가? (`delete/create` 기본 부여 금지)
- [ ] `helm upgrade`에 `--atomic` 플래그가 포함되어 있는가?
- [ ] Deployment에 `readinessProbe`가 설정되어 있는가?
- [ ] HPA `behavior.scaleDown.stabilizationWindowSeconds`가 설정되어 있는가?
- [ ] 로컬에서 통과한 kubectl 명령을 EKS에서도 ServiceAccount 권한으로 검증했는가?
