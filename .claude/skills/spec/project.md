# /spec 적용 값 — promotion 프로젝트

범용 원칙은 같은 폴더의 `SKILL.md`. 이 파일은 이 프로젝트에만 해당하는 값이다.

## 위치
| 대상 | 위치 |
|------|------|
| 문서 | `docs/specs/{kebab-case-이름}.md` (양식: `template.md`) |
| API 계약 | `contracts/openapi.yaml`(serverB), `contracts/server-a-openapi.yaml`(serverA). 프론트는 openapi-typescript로 타입 생성 |
| 서비스 구성 참고 | `docs/arch-snapshot.md` — 관련 도메인만 발췌해서 읽는다 |

## 계약 파일은 구현 PR에서 수정
serverA·serverB의 `OpenApiContractTest`가 실제 `/v3/api-docs`와 계약 파일을 비교한다. 문서 단계에서 계약 파일을 고치면 구현 전까지 CI가 실패한다. 설계에는 YAML 조각으로 적고, 계약 파일 수정은 구현 PR에서 코드와 함께 한다 (`api` skill 규칙과 같음).

## 작업 등록 (3단계)
CLAUDE.md "백로그 관리"의 백로그 위치를 따른다.
- 이슈 트래커(Linear 등)로 지정돼 있고 도구가 연결돼 있으면 → 트래커에 이슈 등록, 본문에 문서 경로를 적는다.
- 아니면 → `docs/backlog.md`. 이미 있는 항목은 고쳐서 쓰고 새 ID는 새 작업에만 준다. `/spec` 시작 시 해당 항목을 "진행 중"으로 옮기고 `문서` 칸에 문서 경로를 적는다.
- 지정과 연결 상태가 다르면 사용자에게 묻는다.

## 이 프로젝트에서 특히 볼 것
- 서비스 간 흐름은 Kafka Saga(serverA 주문 ↔ serverC 결제)다. 이벤트는 필드 추가만 하고 소비자 먼저 배포한다(확장-축소, 예: BL-004). 멱등 키를 정한다.
- 결제 결과를 모르는 경우(UNKNOWN)와 늦게 도착한 결과를 장애 시 동작에 넣는다.
- 새 Gradle 의존성은 사용자 승인 대상이다 (CLAUDE.md "절대 금지 사항").
