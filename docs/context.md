# 맥락 노트: K8s 환경 DiscoveryClient 서비스 조회 복구

## 왜 이 방식을 선택했는가
K8s에는 discovery-service(Eureka 서버)를 배포하지 않는다(Helm 템플릿 없음, Service DNS로 대체). 그런데 serverA `PaymentQueryClient`, cs-bot `CsBotClient`, aiops `CodebotClient`는 `DiscoveryClient.getInstances()`로 대상 서비스를 찾는다. K8s에서는 인스턴스가 비어 다음처럼 동작한다.
- serverA: 결제조회가 항상 UNKNOWN → 타임아웃 주문은 결제 성공이어도 EXPIRED, DB 재대사는 영구 보류
- cs-bot: 주문·결제·상품·프로필 조회 Tool 전부 `IllegalStateException`. 게다가 k8s 프로필 파일이 없어 Eureka 클라이언트가 켜진 채 없는 서버에 등록 재시도
- aiops: Slack → codebot 라우팅이 항상 "연결할 수 없음"

Spring Cloud Commons에 기본 포함된 `SimpleDiscoveryClient`에 K8s Service DNS를 정적 등록하면 `CompositeDiscoveryClient`가 이를 반환한다. Java 코드와 의존성 변경 없이 k8s 프로필 yml만으로 해결되고, local(Eureka) 동작은 그대로다. 사용자가 이 방식을 선택했다 (2026-11-12). cs-bot은 Eureka 비활성화도 함께 하기로 했다.

## 검토했으나 채택하지 않은 대안
### 대안 A: 고정 URL 프로퍼티(`external.*.url`)로 전환
- 무엇: 클라이언트에서 `DiscoveryClient`를 빼고 `@Value` URL 사용 (aiops `serverAAdminClient`가 이 방식)
- 왜 안 썼나: Java·테스트·docker-compose 환경변수까지 변경 범위가 커짐. 정적 등록으로 같은 효과를 yml 하나로 얻음

### 대안 B: spring-cloud-kubernetes discovery 도입
- 무엇: K8s API로 Service 엔드포인트를 동적 조회
- 왜 안 썼나: 새 의존성 + ServiceAccount RBAC(endpoints get/list) 추가 필요. Service DNS가 이미 로드밸런싱하므로 이득이 적음

### 대안 C (cs-bot): 정적 등록만 하고 Eureka 유지
- 왜 안 썼나: 없는 discovery-service에 등록 재시도 에러 로그가 계속 남음. 다른 모듈 k8s 프로필과도 불일치

## 기존 코드베이스 컨벤션
- k8s 전용 설정: `{module}/src/main/resources/application-k8s.yml` (Helm `SPRING_PROFILES_ACTIVE=k8s`)
- 서비스 주소: Helm `values.yaml`의 `name`/`port` = K8s Service DNS (gateway `application-k8s.yml`과 동일 주소 사용)
- 테스트 구조 예시: `serverA/src/test/java/promotion/serverA/config/K8sDiscoveryConfigTest.java` — ApplicationContextRunner + YAML 로드 + `UtilAutoConfiguration`(InetUtils 제공) + `SimpleDiscoveryClientAutoConfiguration`

## 관련 파일/위치
- `serverA/src/main/java/promotion/serverA/client/PaymentQueryClient.java` — serverC 결제조회
- `cs-bot/src/main/java/csbot/csbot/client/CsBotClient.java` — serverA/B/C, user-service 조회
- `aiops/src/main/java/aiops/aiops/router/CodebotClient.java` — codebot 조회
- `helm/promotion-app/values.yaml` — Service 이름/포트 원본

## 외부 참조
- Spring Cloud Commons — SimpleDiscoveryClient (`spring.cloud.discovery.client.simple.instances`)
