---
description: Codex MCP를 read-only 샌드박스로 실행해 현재 브랜치 변경사항을 코드 리뷰한다. 파일 수정 없이 검토 보고서만 출력.
---

# /codex-review 명령어

이 명령어를 실행하면 아래 순서대로 수행한다.

---

## Step 0. 아키텍처 컨텍스트 로딩

다음 두 파일을 읽어 리뷰 컨텍스트로 유지한다.

- `docs/arch-snapshot.md`: 시스템 전체 구조 (Kafka 토픽 맵, 이벤트 흐름, 엔티티, 엔드포인트)
  - 없으면 "arch-snapshot 없음 — /arch-snapshot 실행을 권장합니다" 출력 후 Step 1 진행
- `docs/infra-diagram.md`: 인프라 토폴로지 (컨테이너 구성, 포트, 모니터링 스택)
  - 없으면 조용히 건너뛴다
- `docs/design-notes.md`: 비자명한 설계 결정 및 불변식 (동시성 제어, 패턴 선택 이유 등)
  - 없으면 건너뛴다.

---

## Step 1. 리뷰 대상 수집

### 1-1. 범위 자동 결정 (우선순위 순)

```powershell
# 1순위: 스테이징된 파일이 있으면 → 커밋 직전 리뷰
git diff --cached --name-only

# 2순위: 스테이징이 비어 있으면 → 브랜치 전체 리뷰 (PR 전 리뷰)
git diff main...HEAD --name-only

# 3순위: 둘 다 없으면 → 사용자에게 알리고 중단
```

결정된 범위에 따라 diff 수집 (Java/설정 파일만):

```powershell
# 1순위일 때
git diff --cached -- "*.java" "*.yml" "*.yaml" "*.xml" "*.sql" "*.gradle" "*.properties"

# 2순위일 때
git diff main...HEAD -- "*.java" "*.yml" "*.yaml" "*.xml" "*.sql" "*.gradle" "*.properties"
```

### 1-2. 사전 검증

- 총 diff 라인 수가 **3,000줄 초과**면 사용자에게 경고하고 계속할지 확인한다.
- 변경 파일이 0개면 중단한다.

---

## Step 2. mcp__codex__codex 호출

아래 파라미터로 `mcp__codex__codex` 도구를 호출한다.

```
sandbox:           "read-only"
approval-policy:   "never"
cwd:               현재 작업 디렉토리 (Windows 절대경로)
base-instructions: <아래 BASE_INSTRUCTIONS 전체>
prompt:            <아래 PROMPT_TEMPLATE에 Step 1 결과를 채워 넣은 것>
```

---

[점검 순서 및 원칙]

1. 카파시 4원칙 위반 검사
    - 수술적 변경: 무관한 라인(포맷팅, 주석) 수정이나 사전 코드를 임의 삭제했는가?
    - 단순함 우선: 오버엔지니어링(불필요한 추상화)이나 200줄로 짠 50줄짜리 코드가 있는가?
    - 목표 기반 실행: 새 기능/버그 수정에 대한 의미 있는 테스트가 포함되었는가?

2. Spring 및 JPA 특화 점검
    - OSIV 방치 여부 (open-in-view=false)
    - N+1 쿼리 (fetch join / EntityGraph 누락)
    - Controller에서 Entity 직접 노출 및 반환 (DTO 변환 필수)
    - @Transactional 범위 안에서 외부 HTTP 호출 유무
    - Self-invocation (클래스 내부에서 @Transactional 메서드 직접 호출 여부)
    - @Enumerated(ORDINAL) 사용 등 데이터 오염 리스크

3. 보안 및 정확성
    - SQL 인젝션, XSS, IDOR, 경로 탐색 가능성
    - 민감정보(토큰, 비밀번호) 노출 여부
    - 인증/인가 누락 엔드포인트
    - 동시성 문제 (race condition) 및 트랜잭션 부분 실패 시 일관성

4. 비동기 흐름 분석 (arch-snapshot 컨텍스트 활용)
    - 변경된 코드가 Kafka 토픽 발행/소비에 영향을 주는지 확인
    - arch-snapshot의 이벤트 흐름과 변경 코드의 부수 효과 비교
    - 복잡한 흐름(Saga, Outbox, CDC 구간 등)을 설명할 때는 반드시 마크다운 텍스트 대신 아래 형식의 Mermaid 코드블록으로 출력한다:

```
\`\`\`mermaid
sequenceDiagram
    ...
\`\`\`
```

출력 형식 — 이 마크다운 형식을 정확히 따른다:

# 코드 리뷰 보고서

**리뷰 대상**: <변경 파일 목록>
**리뷰 범위**: <staged / branch(main...HEAD) 중 하나>

---

## 📋 변경 범위 검토
- 범위 일치 여부: ✅ 일치 / ⚠️ 부분 일치 / ❌ 이탈
- 무관한 변경: (있다면 파일:라인 명시)

---

## 🔴 치명적 (반드시 수정)
보안 취약점, 데이터 손실 가능성, 명백한 버그 (없으면 "없음" 한 줄만 표기)

### [N] <한 줄 제목>
- **위치**: `경로/파일.java:라인`
- **문제**: 구체적 설명 및 근거
- **권장 수정**: 어떻게 고치면 되는지 (코드 예시 가능)

---

## 🟡 권장 (가능하면 수정)
가독성, 유지보수성, 사소한 성능 이슈 (없으면 "없음" 한 줄만 표기)

### [N] <한 줄 제목>
- **위치**: `경로/파일.java:라인`
- **문제**: 구체적 설명
- **권장 수정**: 어떻게 고치면 되는지

---

## 🟢 잘된 점
작성자가 잘한 부분을 구체적으로 1~3개. 빈말 금지.

---

## 최종 판정
**판정**: APPROVE / NEEDS_REVISION / REJECT
**근거**: 1~2문장
**다음 단계**: 작성자가 구체적으로 무엇을 해야 하는지

---

## PROMPT_TEMPLATE

```
다음은 리뷰 대상 변경사항이다. 위 지시에 따라 코드 리뷰를 수행하라.

## 리뷰 범위
[staged 또는 branch(main...HEAD) 명시]

## 변경된 파일 목록
[git diff --name-only 결과 삽입]

## 변경 내용 (diff)
[필터링된 git diff 결과 삽입]
```

---

## Step 3. 리뷰 결과 처리

1. Codex 보고서를 사용자에게 그대로 출력한다.
2. 판정에 따라 다음 행동을 취한다:

   - **APPROVE**: "머지 가능합니다." 메시지로 마무리.
   - **NEEDS_REVISION**: 치명적 이슈 목록만 요약해서 재출력. "수정을 진행할까요?" 확인.
   - **REJECT**: 이유를 한 줄로 요약. "어떻게 진행할지 알려주세요." 대기.

3. 사용자가 수정을 승인하면, **내(Claude)가 직접 해당 파일들을 수정**한다. (Codex는 읽기 전용이므로 절대 수정 권한이 없다). 수정 후 결과를 사용자에게 보고한다.