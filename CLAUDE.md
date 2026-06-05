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
- Windows + PowerShell 환경. bash 명령어 사용 금지.

## 파일 읽기 규칙
- 지시된 파일을 우선 읽되, 해당 파일만으로 판단이 불가능한 경우에만 관련 파일을 최소한으로 추가 참조할 것. 불필요한 탐색 금지.

## 작업 시작 시
- 기존에 진행 중이던 `/plan` 기반 대규모 작업을 이어서 할 때는, 코딩 시작 전 반드시 `docs/plan.md`, `docs/context.md`, `docs/checklist.md` 세 파일을 모두 읽고 제약 조건과 다음 진행할 단계를 스스로 파악할 것. 단순 확인/수정에는 불필요.
- 변경 파일이 2개 이상인 작업은 반드시 `/plan` 명령어부터 실행할 것. 곧바로 코딩 시작 금지.
- `/plan` 승인 후 구현 시작 전, 반드시 아래 두 가지를 사용자에게 먼저 확인할 것:
  1. **브랜치**: 새로운 기능/작업이면 브랜치 분리를 확인할 것. 브랜치가 없으면 브랜치명을 제안하고 생성 여부를 물어볼 것. 현재 브랜치 작업의 버그 수정·보완·후속 작업이면 같은 브랜치에서 계속할 것. 파일 수가 기준이 아니라 작업의 성격이 기준이다.
- API/컨트롤러/DTO 작업 시 `.claude/skills/api` 매뉴얼을 활용할 것.
- 엔티티/리포지토리/쿼리/트랜잭션 작업 시 `.claude/skills/jpa` 매뉴얼을 활용할 것.
- 예외 처리/에러 응답 작업 시 `.claude/skills/exception` 매뉴얼을 활용할 것.
- 테스트 작성 시 `.claude/skills/testing` 매뉴얼을 활용할 것.
- Kafka 컨슈머/프로듀서/토픽/DLT/Debezium 작업 시 `.claude/skills/kafka` 매뉴얼을 활용할 것.
- Kubernetes/Helm/kubectl/HPA/RBAC/values.yaml/Deployment 작업 시 `.claude/skills/k8s` 매뉴얼을 활용할 것.

## 장기 실행 명령 처리 원칙

**블로킹 대기는 토큰 낭비다. `run_in_background: true`로 실행하고 완료 알림 후 이어서 처리하라.**

- `docker compose build`, `docker compose up`, `helm upgrade`, `gradle build` 등 완료까지 시간이 걸리는 명령은 반드시 `run_in_background: true`로 실행할 것.
- 완료 알림이 오면 그때 결과를 확인하고 다음 단계를 이어서 처리할 것.
- 직접 실행하며 응답을 기다리는 방식(블로킹) 금지.

## 테스트 분업 원칙

**작성(write)과 실행(run)은 다르다. 토큰을 쓰는 건 실행 로그이지, 코드 작성이 아니다.**

| 작업 | 담당 |
|------|------|
| 테스트 코드 작성/수정/추가 | Claude (구현과 함께) |
| `gradlew test` 실행 | 사용자 직접 |
| 실패 로그 붙여넣기 → 수정 | Claude |

- 프로덕션 코드 변경 시 → 대응하는 테스트도 반드시 함께 수정할 것.
- 새 클래스 추가 시 → 테스트 파일도 함께 생성할 것.
- 새 메서드 추가 시 → 테스트 코드도 함께 생성할 것.
- 테스트를 실행하지 않더라도 "이 테스트를 실행해보세요"라고 명시할 것.

## Spring Boot 공통 규칙
- Controller → Service → Repository 레이어 분리. Controller에 비즈니스 로직 금지.
- Entity를 Controller에서 직접 반환 금지. 반드시 DTO 변환.
- Controller에 @Transactional 금지. Service에만.
- Entity에 @Data/@Setter 금지. @Getter + 도메인 행위 메서드.
- open-in-view: false 필수. findAll() 후 in-memory 필터링 금지.
- 민감 정보(비밀번호, 토큰) 로그/응답 노출 금지.

## 절대 금지 사항
- `.env` 파일은 절대 읽거나 수정하지 말 것.
- `main` 브랜치에 직접 push 금지. 항상 별도 브랜치 사용.
- 새 의존성(Gradle dependency) 추가 시 반드시 나에게 먼저 확인을 받을 것.
- 사용자가 명시적으로 요청하지 않은 파일 삭제 금지. 의심되면 먼저 물어볼 것.

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

## 보고 형식
작업 완료 시 다음 형식으로 보고할 것:
- ✅ 완료한 것: ...
- 🔍 검증 결과: (실행한 명령어와 결과)
- ⚠️ 발견했지만 손대지 않은 것: (있는 경우)
- ❓ 확인 필요 사항: (있는 경우)