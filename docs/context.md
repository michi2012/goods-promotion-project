# 맥락 노트: build.gradle MSA 독립화 + docker-compose 3파일 분리

## 왜 이 방식을 선택했는가

현재 루트 `subprojects {}` 방식은 Gradle 모노레포에서 공통 설정을 중앙화하는 정석이나,
MSA 배포 단위로 각 서버를 독립시키려면 각 모듈이 루트에 의존하지 않고 자기 완결적으로 빌드돼야 한다.
`apply false` 방식은 루트에서 플러그인 버전만 선언하고 실제 적용은 각 서브모듈이 담당하는 Gradle 표준 패턴이다.

docker-compose 3파일 분리(infra/app/monitoring)는 사용자 요청이며, 배포 단위를 인프라/앱/모니터링으로 명확히 구분한다.
로컬 개발 편의를 위해 기존 통합 `docker-compose.yml`은 유지한다.

## 검토했으나 채택하지 않은 대안

### 대안 A: 루트 subprojects 유지 + 컨벤션 플러그인
- 무엇: 별도 Gradle 플러그인 모듈을 만들어 공통 설정을 캡슐화
- 왜 안 썼나: 오버엔지니어링. 현재 규모에서는 각 서버 build.gradle에 직접 작성하는 게 더 단순하고 명확하다.

### 대안 B: docker-compose 2파일 분리 (비즈니스 / 모니터링)
- 무엇: infra(mysql/redis/kafka)를 app과 같은 파일에 묶는 방식
- 왜 안 썼나: 사용자가 3파일을 선택함. 인프라를 별도로 분리하면 DB만 재시작하거나 Kafka만 교체할 때 앱에 영향 없이 가능하다.

### 대안 C: 폴리레포 (서버마다 별도 Git 저장소)
- 무엇: serverA, serverB, serverC를 각각 독립 레포로 분리
- 왜 안 썼나: 사용자가 모노레포 유지를 원함. 로컬 테스트 편의성 우선.

## 핵심 결정 사항

### 크로스 컴포즈 depends_on 처리
분리된 컴포즈 파일 간에는 `depends_on`이 동작하지 않는다. 대신 `restart: on-failure`로 자동 재시도에 의존하며, 배포 순서(infra → app → monitoring)를 관례로 정한다.

### shared-logs volume 처리
app 서버들이 `/logs`에 쓰고 vector(monitoring)가 읽는 볼륨. 두 컴포즈에 걸쳐 있으므로 `external: true`로 선언하고 사전 생성이 필요하다.
볼륨 이름: `promotion-shared-logs` (name 명시로 컴포즈 프로젝트명 접두사 방지)

### redpanda-console 위치
kafka에 의존하므로 infra 컴포즈에 포함. 모니터링 도구이지만 인프라 레이어의 Kafka UI로 분류.

### aiops 위치
Spring Boot 앱이지만 alertmanager/prometheus/loki/tempo를 depends_on하므로 monitoring 컴포즈에 포함.

## 관련 파일/위치
- `build.gradle` — 루트, subprojects 제거 대상
- `serverA/build.gradle`, `serverB/build.gradle`, `serverC/build.gradle`, `aiops/build.gradle` — 독립화 대상
- `docker-compose.yml` — 로컬 개발용 통합본, 변경 없음
- `docker-compose.infra.yml` — 신규, mysql/redis/kafka/debezium
- `docker-compose.app.yml` — 신규, serverA/B/C
- `docker-compose.monitoring.yml` — 신규, prometheus/grafana/loki/tempo/otel/aiops 등
