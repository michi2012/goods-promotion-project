# CLAUDE.md

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:

[Step] → verify: [check]
[Step] → verify: [check]
[Step] → verify: [check]


Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.


---

# 프로젝트 운영 규칙

위 4가지 원칙(Think Before Coding / Simplicity First / Surgical Changes / Goal-Driven Execution)은 모든 작업에 항상 적용된다. 아래는 이 프로젝트에 한정된 추가 규칙이다.

## 빌드 환경
- 멀티 모듈 Gradle 프로젝트. gradlew.bat은 루트에 있음.
- 로컬 OS·셸 규칙은 개인 환경마다 다르므로 각자의 `CLAUDE.local.md`(git 미추적)에 둔다.

## 파일 읽기 규칙
- 지시된 파일을 우선 읽되, 해당 파일만으로 판단이 불가능한 경우에만 관련 파일을 최소한으로 추가 참조할 것. 불필요한 탐색 금지.

## docs/ 파일 관리 원칙

`docs/plan.md`, `docs/context.md`, `docs/checklist.md`는 **PR이 머지되기 전까지 누적**하는 작업 기록이다.

- 이전 작업의 PR이 **아직 머지 전**이면(브랜치 안에서 커밋은 했더라도) → 새 관련 작업을 각 파일에 섹션으로 **추가**한다. 덮어쓰기 금지.
- 이전 작업의 PR이 **머지 완료**됐으면 → 다음 /plan 시 파일을 새로 작성한다.
- 섹션 제목 형식: `## [완료] 작업명` / `## [진행 중] 작업명`
- checklist의 "발견 사항"은 임시 기록이다. 작업을 커밋하기 전에 백로그로 옮긴다 ("백로그 관리" 참조).

## 백로그 관리
- 로드맵·후속 작업·범위 밖 발견 사항은 단일 백로그에서 관리한다. **백로그 위치: `docs`** → `docs/backlog.md` (이슈 트래커 도입 시 이 값을 바꾸고 미완료 항목을 옮긴다. 두 곳에 동시 기록 금지)
- 진행 중 작업의 세부는 3종 문서, 결정 이유는 `docs/design-notes.md`. 백로그에는 넣지 않는다.
- 항목 추가 시 백로그 상단의 "다음 ID"를 쓰고 1 올린다. 바로 착수 가능하면 "진행 예정"의 우선순위 위치에 삽입하고, 선행 조건이 있으면 "EKS 재가동 후"·"조건부" 섹션에 넣는다. 규모(S/M/L)를 함께 적는다.
- `/plan` 시작 시 해당 항목을 "진행 중"으로, 작업 중 발견 사항은 checklist에 임시 기록 → **커밋 전 백로그로 이전**.
- 완료 처리는 **같은 PR 안에서** 한다: PR 생성 직후 같은 브랜치에 백로그 "완료"(PR 번호 포함) + 3종 문서 `[완료]` 처리 커밋을 추가해 push한다. 완료 처리만을 위한 별도 PR은 만들지 않는다.
- 남은 작업·로드맵 질문에는 백로그를 기준으로 답한다.

## 대형 설계 문서 관리 원칙
`docs/system-design.md`, `docs/arch-snapshot.md` 등 설계/아키텍처 문서가 일정 규모(약 500줄) 이상으로 커지면, 매 작업마다 전체를 읽지 않는다.
- 비대해진 문서는 도메인별로 분할한다 (예: `docs/domains/{도메인}/arch.md`).
- 분할 전이라도, 작업과 관련된 키워드(도메인명, 기능명)로 필요한 부분만 발췌해서 읽는다.

## 작업 시작 시
- 기존에 진행 중이던 `/plan` 기반 대규모 작업을 이어서 할 때는, 코딩 시작 전 반드시 `docs/plan.md`, `docs/context.md`, `docs/checklist.md` 세 파일을 모두 읽고 제약 조건과 다음 진행할 단계를 스스로 파악할 것. 단순 확인/수정에는 불필요.
- 변경 파일이 2개 이상인 작업은 반드시 `/plan` 명령어부터 실행할 것. 곧바로 코딩 시작 금지. 단, 문서(`*.md`)만 바꾸는 변경은 제외.
- `/plan` 승인 후 구현 시작 전, 반드시 아래 사항을 사용자에게 먼저 확인할 것:
  1. **브랜치**: 새로운 기능/작업이면 브랜치 분리를 확인할 것. 브랜치가 없으면 브랜치명을 제안하고 생성 여부를 물어볼 것. 현재 브랜치 작업의 버그 수정·보완·후속 작업이면 같은 브랜치에서 계속할 것. 파일 수가 기준이 아니라 작업의 성격이 기준이다.
- API/컨트롤러/DTO 작업 시 `.claude/skills/api` 매뉴얼을 활용할 것.
- 엔티티/리포지토리/쿼리/트랜잭션 작업 시 `.claude/skills/jpa` 매뉴얼을 활용할 것.
- 예외 처리/에러 응답 작업 시 `.claude/skills/exception` 매뉴얼을 활용할 것.
- 테스트 작성 시 `.claude/skills/testing` 매뉴얼을 활용할 것.
- Kafka 컨슈머/프로듀서/토픽/DLT/Debezium 작업 시 `.claude/skills/kafka` 매뉴얼을 활용할 것.
- Kubernetes/Helm/kubectl/HPA/RBAC/values.yaml/Deployment 작업 시 `.claude/skills/k8s` 매뉴얼을 활용할 것.
- E2E·전체 흐름 검증·사전 점검·스모크 테스트 작업 시 `.claude/skills/e2e` 매뉴얼을 활용할 것.
- 프론트엔드 화면/컴포넌트 작업 시 `.claude/skills/frontend`, 프론트 테스트 작성 시 `.claude/skills/frontend-testing`, 렌더링 결과를 보며 디자인을 다듬을 때 `.claude/skills/frontend-design-feedback` 매뉴얼을 활용할 것.

## 디버깅 루프 탈출 조건
같은 이슈(테스트 실패, 빌드 오류 등)를 해결하기 위한 첫 수정을 시도하기 전, 현재 작업 트리 상태를 체크포인트로 기록한다 (예: `git stash` 후 즉시 재적용, 또는 변경 파일 목록 기록).
동일한 이슈를 해결하기 위해 3회 연속 수정을 시도했음에도 실패하면, 추가 수정을 시도하지 말고 즉시 중단한다. 수정 시도로 변경된 파일만 체크포인트 상태로 되돌리고(디버깅과 무관한 미커밋 변경은 건드리지 않는다), 지금까지 시도한 방법과 마지막 에러를 정리해 사용자에게 보고하고 다음 방향을 확인한다.

## 단계 실행 묶음 허용 기준 (리스크 등급)
`/plan` Step 6은 기본적으로 한 단계씩 실행하고 검증한다. 다만 아래 기준에 따라 연속된 단계를 묶어 실행할 수 있다.
- **묶음 실행 가능 (낮은 리스크)**: 테스트 코드 작성, 문서 업데이트, 단순 CRUD/DTO 추가 등 git으로 쉽게 되돌릴 수 있는 변경.
- **단계별 승인 유지 (높은 리스크)**: DB 마이그레이션, 인프라(Helm/k8s/Kafka 등) 변경, 새 의존성 추가, 브랜치/커밋/푸시.
- 묶음 실행 시에도 각 단계 완료 후 검증과 `docs/checklist.md` 업데이트는 동일하게 수행한다.

## 장기 실행 명령 처리 원칙

**블로킹 대기는 토큰 낭비다. `run_in_background: true`로 실행하고 완료 알림 후 이어서 처리하라.**

- `docker compose build`, `docker compose up`, `helm upgrade`, `gradle build` 등 완료까지 시간이 걸리는 명령은 반드시 `run_in_background: true`로 실행할 것.
- 완료 알림이 오면 그때 결과를 확인하고 다음 단계를 이어서 처리할 것.
- 직접 실행하며 응답을 기다리는 방식(블로킹) 금지.

## 테스트 분업 원칙

**기준은 "누가 실행하냐"가 아니라 "실행 비용과 환경 의존성"이다.** PR CI(`backend-ci`·`frontend-ci`)가 재검증하는 영역(백엔드 테스트, 프론트 lint·Vitest·build)에서는 로컬 실행이 속도 최적화일 뿐이라 Claude가 직접 돌려도 안전하다. CI가 돌리지 않는 영역(E2E — 백엔드 E2E·UI E2E)은 로컬 실행이 유일한 방어선이라 오히려 Claude가 더 적극적으로 돌려야 한다.

| 테스트 종류 | 예시 | 실행 담당 |
|------|------|------|
| 빠르고 외부 인프라 불필요 | 백엔드 단위/슬라이스(`@WebMvcTest`, `@DataJpaTest`, Mockito 순수 단위, 외부 인프라 없이 뜨는 `@SpringBootTest`), 프론트 `npm run test`(Vitest) | **Claude가 직접 실행.** 백엔드는 방금 작성/수정한 클래스만 `--tests` 필터로, 프론트는 전체 스위트로. 통과할 때까지 수정 |
| 외부 인프라 필요 또는 전체 스위트 | Testcontainers, 다중 모듈 `gradlew test`, 백엔드 E2E `backend-e2e/ npm test`(실제 백엔드 필요), UI E2E `npm run test:e2e`(브라우저+실제 백엔드 필요) | 필요한 환경(DB/Redis/Kafka/백엔드 서버 등)이 **이미 떠 있으면** Claude가 직접 실행. 없으면 사용자에게 실행을 요청 — Claude가 그 인프라를 새로 띄우면서까지 실행하지는 않음(그건 "커밋 전 로컬 E2E 검증" 규칙의 영역) |

- 프로덕션 코드 변경 시 → 대응하는 테스트도 반드시 함께 수정할 것.
- 새 클래스 추가 시 → 테스트 파일도 함께 생성할 것.
- 새 메서드 추가 시 → 테스트 코드도 함께 생성할 것.
- Claude가 직접 실행한 경우 → 실행한 명령어와 결과를 보고에 포함할 것.
- Claude가 실행하지 못한 경우(환경 없음) → "이 테스트를 실행해보세요"라고 명시할 것.
- 같은 이슈로 Claude의 직접 실행이 3회 연속 실패하면 "디버깅 루프 탈출 조건"을 그대로 적용한다 — 추가 수정 시도 중단, 체크포인트로 되돌리고 사용자에게 보고.

## 프론트엔드 테스트 강도 원칙
프론트엔드 변경 시 테스트 강도를 변경 범위에 따라 차등 적용한다.
- 핵심 퍼널(P0) 기능 변경: E2E + 시각 회귀 테스트(`frontend-testing`)까지 수행.
- 단순 컴포넌트 추가/수정: 단위 테스트(Vitest + RTL)로 충분, E2E/시각 회귀는 생략 가능.
- P0 여부가 불명확하면 임의로 판단하지 말고 사용자에게 확인한다.

## Spring Boot 공통 규칙
- Controller → Service → Repository 레이어 분리. Controller에 비즈니스 로직 금지.
- Entity를 Controller에서 직접 반환 금지. 반드시 DTO 변환.
- Controller에 @Transactional 금지. Service에만.
- Entity에 @Data/@Setter 금지. @Getter + 도메인 행위 메서드.
- open-in-view: false 필수. findAll() 후 in-memory 필터링 금지.
- 민감 정보(비밀번호, 토큰) 로그/응답 노출 금지.

## DB 마이그레이션 작성 원칙
엔티티의 컬럼명/타입 변경을 컬럼 삭제 후 재추가(DROP + ADD)로 처리하지 않는다 — `ALTER`/`MODIFY`/`RENAME COLUMN` 구문으로 작성한다 (`/db-migration` 활용).
마이그레이션 SQL은 파일 생성까지만 하고, 실제 DB 적용(실행)은 사용자가 한다.
serverA·serverC·user-service는 Flyway라 서버 기동 시 마이그레이션이 자동 적용된다. 새 마이그레이션이 포함된 변경은 E2E 등으로 서버를 띄우기 전에 사용자 확인을 받는다.

## 절대 금지 사항
- `.env` 파일은 절대 읽거나 수정하지 말 것.
- `main` 브랜치에 직접 push 금지. 항상 별도 브랜치 사용.
- 새 의존성(Gradle dependency) 추가 시 반드시 나에게 먼저 확인을 받을 것.
- 사용자가 명시적으로 요청하지 않은 파일 삭제 금지. 의심되면 먼저 물어볼 것.
- kubectl/helm 명령을 Java에서 조합할 때 문자열 concat 금지. 반드시 `List<String>` 형태로 명령어를 구성할 것 (커맨드 인젝션 방지).

## design-notes.md 업데이트 규칙
구현 완료(checklist 전체 완료) 후, 다음 조건을 **모두** 만족할 때만 `docs/design-notes.md`를 업데이트한다:
1. 코드만 봐서는 "왜 이렇게 했는지" 알 수 없는 결정
2. 미래 구현에 영향을 주는 제약이나 불변식
3. 대안을 검토했지만 채택하지 않은 이유가 있는 경우

위 조건에 해당하지 않으면 업데이트하지 않는다. plan/context.md에만 기록하면 충분하다.
업데이트 시에는 기존 섹션을 덮어쓰지 말고, 해당 도메인 섹션에 항목을 추가하거나 수정한다.

## 코드 작성 후 자가 점검
모든 작업 완료 시 다음을 스스로 확인하고 보고할 것:
1. 변경한 모든 라인이 내 요청과 직접 연결되는가? (수술적 변경 원칙)
2. 검증(테스트/명령어 실행)을 거쳤는가? (목표 기반 실행 원칙)
3. 발견했지만 손대지 않은 죽은 코드/이슈가 있다면 보고에 포함했는가?

## 커밋 단위 원칙

**전체 구현 완료 → 테스트 통과 → 기능별 커밋. 중간 커밋 금지.**

- plan 단계별로 커밋하지 마라. 단계 중간은 동작하지 않는 상태다.
- 모든 단계 구현이 끝나고 테스트(gradle build, helm template 등)가 통과한 후 커밋한다.
- 커밋은 "이 커밋만 봤을 때 의미 있고 동작하는 변경인가?"를 기준으로 나눈다.
- 전형적인 묶음 예시:
  - Helm 인프라 변경 (차트 추가/수정) → 1 커밋
  - 연관된 Java 코드 변경 (A.java가 B.java를 필요로 하면 같이) → 1 커밋
  - RBAC/설정 변경 → 1 커밋
  - 문서 업데이트 → 1 커밋
- 커밋 나누기는 구현 완료 후 `git add <파일...>`로 파일 단위로 골라서 정리한다.
- main은 squash 머지라 PR당 커밋 1개로 합쳐진다. 브랜치 안의 커밋 분리는 리뷰용이고, main에서 되돌리는(revert) 단위는 PR이다.

## PR 범위 원칙
- PR 하나는 목적 하나. 작업 중 문제를 발견하면 **고치기 전에** 분류한다:
  - 이 PR을 막는 문제(안 고치면 기능 미동작·테스트 실패) → 같은 PR에 별도 커밋
  - 막지 않는 문제 → checklist "발견 사항"에 적고 원래 작업 계속 (커밋 전 백로그로 이전)
  - 분류가 애매하거나 급해 보이는 문제 → 고치기 전에 사용자에게 묻는다. 급한 무관 수정은 main에서 새 브랜치를 따 별도 PR로.
- PR이 리뷰하기 어려운 크기(변경 파일 약 20개 이상)로 커지면 Claude가 먼저 알리고 PR 분리를 제안한다.

## 세션 전환 원칙
- PR 머지·기능 완료 등 작업 단위가 끝났고 대화가 길어졌으면, 새 세션으로 옮기라고 권한다.
- 권하기 전에 다음 세션에 필요한 맥락이 문서에 있는지 확인한다: 백로그 갱신, 3종 문서 정리, 남은 일·다음 작업 한 줄.
- 작업 중간에는 권하지 않는다. 단, 대화가 자동 요약돼 앞선 결정이 흐려졌다면 3종 문서를 갱신한 뒤 권한다.

## 커밋 전 검증
- 로컬에서 실행 가능한 변경은 E2E로 검증한 뒤 커밋한다. 건너뛰려면 먼저 사용자에게 이유를 묻는다 ("환경이 없어서"는 이유가 아님 — 구성 가능하면 구성).
- 클라우드 전용(EKS RBAC, ALB 등)은 `gradle build` + `helm template` 통과 후 커밋하고, 커밋 메시지에 "EKS 배포 후 검증 필요"를 권고한다.
- E2E 원칙과 이 프로젝트의 시나리오·점검 대상·실행 시점은 `.claude/skills/e2e`(SKILL.md + project.md).

## 보고 형식
작업 완료 시 다음 형식으로 보고할 것:
- ✅ 완료한 것: ...
- 🔍 검증 결과: (실행한 명령어와 결과)
- ⚠️ 발견했지만 손대지 않은 것: (있는 경우)
- ❓ 확인 필요 사항: (있는 경우)