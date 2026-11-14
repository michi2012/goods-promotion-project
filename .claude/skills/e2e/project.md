# E2E 적용 값 — promotion 프로젝트

범용 원칙은 같은 폴더의 `SKILL.md`. 이 파일은 이 프로젝트에만 해당하는 값이다.
팀 가정: 백엔드팀·프론트팀이 분리돼 있다고 보고 각 팀이 자기 E2E를 소유한다 (실제 구현은 함께 해도).

## E2E 두 층 — 무엇을 검증하느냐로 나누고, 필요한 것이 준비됐을 때 실행

| 층 | 검증하는 것 | 실행에 필요한 것 | 위치·실행 | 소유 |
|------|------|------|------|------|
| 백엔드 E2E | 서버 간 흐름의 최종 결과 (구매 → 결제 30,000 KRW PAID → 주문 PAID) | 백엔드만 (프론트·브라우저 불필요) | `backend-e2e/` — `npm test`, 시나리오 명세 `backend-e2e/SCENARIOS.md` | 백엔드팀 |
| UI E2E | 사용자가 보는 화면 흐름 (구매 화면 30,000원 → 주문 상태 PAID 표시) | 프론트 + 백엔드 | `frontend/e2e/` — `npm run test:e2e` | 프론트팀 |

| 상황 | 실행 |
|------|------|
| 백엔드만 구현됨 | 백엔드 E2E |
| 프론트만 구현됨 | E2E 불가 — 프론트 컴포넌트 테스트(Vitest + MSW)로 검증 |
| 둘 다 구현됨 | 백엔드 E2E + UI E2E (세 번째 별도 E2E는 없음) |

- 도구: 두 층 모두 Playwright (백엔드 E2E는 `request`만 사용). 선택 이유·REST Assured 전환 조건은 `docs/design-notes.md`.
- 근거: 백엔드 E2E는 단계(①~④)별 응답을 리포트에 첨부하고 trace를 항상 남긴다(`npm run report`). UI E2E는 확인 시점 스크린샷을 첨부한다(`E2E_SCREENSHOTS=1`이면 모든 화면 저장).

## 사전 점검 대상 (HTTP만 사용 — `backend-e2e/preflight.ts`, `frontend/e2e/preflight.ts`)
- serverA·B·C `/actuator/health` → UP (DB·Redis 상태 포함)
- Debezium 커넥터 2개(`promotion-outbox-connector`, `payment-outbox-connector`)와 각 태스크가 RUNNING (Kafka Connect REST 8083)
- 타임아웃 10초, 실패 시 1회 재확인. `E2E_SKIP_PREFLIGHT=1`로 생략 가능

## 로컬 스택 기동 시 주의
- **이미지는 미리 빌드된 jar를 복사한다** (`Dockerfile`: `COPY build/libs/*SNAPSHOT.jar`). 코드를 바꿨으면 `docker compose up --build` 전에 `gradlew :{모듈}:bootJar`를 먼저 실행한다. `gradlew test`는 jar를 만들지 않으므로, 빼먹으면 옛 코드로 E2E가 돈다.
- 백엔드 서버 기동에 1~2분 걸린다(serverA 약 95초). `compose up` 직후 사전 점검은 실패하므로 serverA 로그 `Started ServerAApplication` 또는 `/actuator/health` UP을 확인한 뒤 실행한다.
- E2E용 설정값(스케줄러 주기 등)은 저장소 `docker-compose.yml`을 고치지 말고 임시 override 파일(`-f docker-compose.yml -f <override>`)로 주입한다. `-f`를 쓰면 `docker-compose.override.yml`이 자동으로 안 붙으므로 `-f docker-compose.override.yml`도 함께 지정한다.
- `debezium-init`이 `Restarting (22)`를 반복하고 로그에 `curl: (22) ... error: 409`가 찍히면 → 정상이다. 커넥터가 이미 등록돼 있어서(설정이 이름 있는 볼륨 `promotion_kafka-data`에 남음) 다시 등록하려다 409를 받는 것. 판단은 `GET /connectors/{이름}/status`(8083)가 RUNNING인지로 한다. 재시작이 거슬리면 `docker compose stop debezium-init`.
- 커넥터가 RUNNING인데 결제가 serverC에 안 오면(결제 조회 404) → Kafka Connect 로그에 `Error code: 1236`(binlog 파일 없음) 또는 `log event entry exceeded max_allowed_packet`(같은 이름의 binlog 파일이 새로 만들어져 옛 오프셋 위치가 엉뚱한 곳을 가리킴 — 원인·대처 동일)이 있는지 본다. 원인: MySQL은 익명 볼륨이라 재생성 시 binlog가 새로 시작되는데 커넥터 오프셋은 이름 있는 볼륨 `promotion_kafka-data`에 남는다. 대처: 커넥터별 `PUT /connectors/{이름}/stop` → `DELETE /connectors/{이름}/offsets` → `PUT /connectors/{이름}/resume` (8083).

## 실행 시점

| 시점 | 실행 범위 |
|------|------|
| 로컬 (커밋 전) | 바꾼 부분의 테스트. 백엔드 흐름을 건드렸으면 백엔드 E2E, 화면을 건드렸으면 UI E2E까지 |
| CI – PR마다 | `backend-ci.yml`: 백엔드 모듈별 병렬 테스트(단위·통합·계약, Gradle 빌드 캐시) / `frontend-ci.yml`: lint·Vitest·build. 경로 필터로 바뀐 쪽만 실행. E2E는 돌리지 않음 |
| CI – main 머지 후 / 야간 / 수동 | 전체 스택 E2E — 백엔드 E2E + UI E2E (백로그 BL-007, 미구현) |
| 배포 후 | 스모크 — 백엔드 E2E 재사용. 저장소 변수 `DEPLOY_ENABLED=true`로 배포가 실제로 돌았을 때만 (백로그 BL-018, 미구현) |

## 로컬 검증이 불가능할 때

| 상황 | 커밋 전 검증 기준 |
|------|-----------------|
| 로컬에서 바로 실행 가능 | 로컬 E2E 후 커밋 |
| 기본 환경은 없지만 우회 수단이 있음 (kind, ngrok, docker compose 등) | 우회 수단을 설치·구성해서라도 검증 후 커밋. "귀찮다"는 이유로 생략 금지 |
| 진짜 로컬 불가 (AWS ALB, EKS RBAC 등 클라우드 전용) | `gradle build` + `helm template` 통과 후 커밋. EKS 배포 후 사후 검증 |
| 값 조정, 문서 변경 | 육안 확인으로 충분 |

- **로컬 kubectl 통과 = RBAC 통과가 아니다.** 로컬 kubeconfig는 cluster-admin이므로 ServiceAccount 권한 제약이 전혀 검증되지 않는다. rbac.yaml에서 누락된 권한은 EKS에서만 발견된다.
