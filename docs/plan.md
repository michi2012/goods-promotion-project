# 계획서: K8s 환경 DiscoveryClient 서비스 조회 복구 (serverA / cs-bot / aiops)

- 작성일: 2026-11-12
- 관련 이슈/티켓: 없음 (arch-snapshot 최신화 중 발견)
- 브랜치: `fix/servera-k8s-payment-query`

## [완료] serverA — PaymentQueryClient의 serverC 조회

- `serverA/src/main/resources/application-k8s.yml`: SimpleDiscoveryClient에 `serverC` → `http://server-c:8082` 정적 등록
- `serverA/src/test/java/promotion/serverA/config/K8sDiscoveryConfigTest.java`: 바인딩 검증 테스트 (통과)

## [완료] cs-bot / aiops — 동일 방식 적용

### 목표
K8s(Eureka 없음)에서 cs-bot `CsBotClient`와 aiops `CodebotClient`가 `DiscoveryClient`로 대상 서비스를 찾을 수 있게 한다.

### 성공 기준
- [ ] cs-bot k8s 프로필 로드 시 `serverA`/`serverB`/`serverC`/`user-service`가 각각 `http://server-a:8080`/`http://server-b:8081`/`http://server-c:8082`/`http://user-service:8086`으로 조회된다 (`gradlew :cs-bot:test --tests "*K8sDiscoveryConfigTest"`)
- [ ] cs-bot k8s 프로필에서 `eureka.client.enabled=false`가 적용된다 (같은 테스트)
- [ ] aiops k8s 프로필 로드 시 `codebot`이 `http://codebot:8087`로 조회된다 (`gradlew :aiops:test --tests "*K8sDiscoveryConfigTest"`)
- [ ] Java 프로덕션 코드 변경 없음 (`git diff --stat`)

### 비범위 (Out of Scope)
- Helm Deployment의 `EUREKA_DEFAULT_ZONE` 환경변수 정리 (K8s에서 미사용 값)
- `DiscoveryClient` → 고정 URL 방식 전환 등 클라이언트 코드 리팩터링
- 실제 K8s(kind/EKS) 클러스터에서의 동작 검증 (커밋 전 별도 결정)

### 단계별 작업 계획

#### 단계 1: cs-bot K8s 프로필 신규 + 테스트
- 변경 파일: `cs-bot/src/main/resources/application-k8s.yml`(신규), `cs-bot/src/test/java/csbot/csbot/config/K8sDiscoveryConfigTest.java`(신규)
- 변경 내용 요약: Eureka 비활성화 + 4개 서비스 정적 등록. serverA 테스트와 같은 구조(ApplicationContextRunner + YAML 로드)로 4개 서비스 URI와 eureka 비활성 값을 검증.
- 검증 방법: `.\gradlew.bat :cs-bot:test --tests "csbot.csbot.config.K8sDiscoveryConfigTest"`
- 롤백 방법: 두 신규 파일 삭제
- 예상 소요: 짧음

#### 단계 2: aiops K8s 프로필 수정 + 테스트
- 변경 파일: `aiops/src/main/resources/application-k8s.yml`, `aiops/src/test/java/aiops/aiops/config/K8sDiscoveryConfigTest.java`(신규)
- 변경 내용 요약: 기존 Eureka 비활성 설정 아래에 `codebot` 정적 등록 추가. 동일 구조 테스트로 URI 검증.
- 검증 방법: `.\gradlew.bat :aiops:test --tests "aiops.aiops.config.K8sDiscoveryConfigTest"`
- 롤백 방법: `git checkout -- aiops/src/main/resources/application-k8s.yml` + 테스트 파일 삭제
- 예상 소요: 짧음

#### 단계 3: 문서 반영
- 변경 파일: `docs/arch-snapshot.md`
- 변경 내용 요약: cs-bot/aiops 경고 문구를 해결 내용으로 교체
- 검증 방법: 육안 확인
- 롤백 방법: 해당 문단 되돌리기
- 예상 소요: 짧음

### 리스크 및 대응
- 리스크: 서비스 ID 키 오타/대소문자 불일치 시 조용히 조회 실패 → 대응: 코드에 쓰인 문자열 그대로 키로 쓰고 테스트에서 동일 문자열로 조회 검증
- 리스크: cs-bot에 처음 생기는 k8s 프로필 파일이 다른 설정과 충돌 → 대응: Eureka·디스커버리 키만 넣어 기존 `application.yaml` 값은 건드리지 않음
- 리스크: 로컬 테스트는 DNS 연결까지 검증하지 못함 → 대응: 커밋 메시지에 "EKS 배포 후 검증 필요" 명시 또는 kind 검증 (사용자 결정)

### 의존성
- Helm `promotion-app/values.yaml`의 Service 이름/포트 (`server-a:8080`, `server-b:8081`, `server-c:8082`, `user-service:8086`, `codebot:8087`)
- spring-cloud-commons `SimpleDiscoveryClient` (이미 클래스패스에 있음, 신규 의존성 없음)
