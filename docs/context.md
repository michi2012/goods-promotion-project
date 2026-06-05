# 맥락 노트: K8s 관제 자동화 (AIOps + 대시보드 + 알람)

- 작성일: 2026-06-05

## 왜 이 방식을 선택했는가

### kubectl ProcessBuilder vs fabric8 K8s client
kubectl ProcessBuilder를 선택. 이유:
- fabric8 의존성을 추가하려면 사용자 승인 필요 (CLAUDE.md 규칙)
- kubectl은 ServiceAccount 인증을 자동으로 처리 (`/var/run/secrets/kubernetes.io/serviceaccount/token`)
- ProcessBuilder로 kubectl 실행 시 pod 내부에서 바로 클러스터와 통신 가능

### Slack Interactive 방식 선택 (A안 — Block Kit 버튼)
curl 명령어 표시(B안) 대신 실제 Block Kit 버튼 선택. 이유:
- 엔지니어가 터미널 접근 없이 Slack에서 바로 승인 가능
- `ActionApprovalController` 에 이미 `TODO: kubectl executor 연결 예정` 주석이 있어 설계 의도 일치

### Slack Interactive 콜백 라우팅
Slack → ALB → Gateway → aiops 경로. 이유:
- aiops 전용 Ingress 규칙을 별도 추가하면 Ingress 복잡도 증가
- gateway에 `/slack/interactive` 라우트 추가만으로 해결 (Rate Limiting 미적용 라우트)
- aiops는 이미 gateway를 통해 `/webhook/**`, `/action/**`가 라우팅 중

### scale only (restart 제외)
kubectl rollout restart는 불필요. 이유:
- liveness/readiness probe가 이미 자동으로 비정상 파드를 재시작
- 수동 restart가 필요한 상황 = probe 설정이 잘못된 것 → 근본 원인 수정이 맞음

### Slack Interactive Signing Secret 검증 생략
이번 범위 밖. 이유:
- 프로덕션에서는 필수지만 포트폴리오/PoC 목적
- 추후 `X-Slack-Signature` HMAC-SHA256 검증 레이어 추가 가능

## 검토했으나 채택하지 않은 대안

### 대안 A: fabric8 K8s Java Client
- 무엇: `io.fabric8:kubernetes-client`로 K8s API 직접 호출
- 왜 안 썼나: 새 의존성 추가에 사용자 승인 필요, kubectl ProcessBuilder가 더 단순

### 대안 B: curl 명령어 Slack 표시 (B안)
- 무엇: 버튼 없이 Slack에 curl 명령어를 코드 블록으로 표시
- 왜 안 썼나: 사용자가 A안(Block Kit 버튼) 선택

### 대안 C: aiops 전용 Ingress 경로
- 무엇: `/slack/interactive` 를 Ingress에서 aiops로 직접 라우팅
- 왜 안 썼나: gateway route 추가가 더 단순하고 기존 패턴과 일치

## 기존 코드 컨벤션

- Spring AI 도구: `@Tool` + `@ToolParam` 어노테이션, `ObservabilityTools.java` 패턴 동일하게 적용
- 도구 메서드: 실패 시 예외 던지지 않고 에러 문자열 반환 (AI가 스킵하고 계속 분석하도록)
- Slack 발송: `SlackNotificationService.send(text)` 패턴 → `sendBlockKit(blocks)` 오버로드 추가
- 승인 흐름: `ActionApprovalService` 인메모리 ConcurrentHashMap + 1시간 TTL 유지
- kubectl 실행 후 Slack 응답은 `response_url`로 POST (메시지 업데이트)

## 관련 파일
- `aiops/src/main/java/aiops/aiops/tools/ObservabilityTools.java` — 기존 도구 패턴 참조
- `aiops/src/main/java/aiops/aiops/approval/ActionApprovalService.java` — 승인 대기열 (TODO 연결)
- `aiops/src/main/java/aiops/aiops/approval/ActionApprovalController.java` — 기존 curl 승인 엔드포인트
- `aiops/src/main/java/aiops/aiops/agent/AiOpsAgentService.java` — 도구 등록 및 시스템 프롬프트
- `helm/promotion-app/templates/aiops/deployment.yaml` — serviceAccountName 추가 대상
- `gateway-service/src/main/resources/application-k8s.yml` — /slack/interactive 라우트 추가 대상

## 외부 참조
- Slack Block Kit Builder: https://app.slack.com/block-kit-builder
- Slack Interactive Components: payload는 `application/x-www-form-urlencoded` + `payload` 필드에 JSON
- kubectl ServiceAccount 자동 인증: `/var/run/secrets/kubernetes.io/serviceaccount/` 자동 마운트
