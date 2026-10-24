# 맥락 노트: 모듈별 코드 흐름 문서화 (flow.md 4종 신규 작성)

## 왜 이 방식을 선택했는가
사용자가 오랜만에 코드베이스를 다시 보는 상황에서 "기억이 잘 안 난다"고 밝혔고, 목적은 코드 복기다. 기존 `docs/aiops-flow.md`/`docs/codebot-flow.md`/`docs/qa/cs-bot-flow.md`가 이미 "진입점 → 클래스/메서드 단위 단계별 흐름"이라는 검증된 패턴을 갖고 있어, 이 패턴을 나머지 미문서화 영역(구매·Saga 핵심, user-service, gateway-service, frontend)에 동일하게 적용하기로 했다.
"이력서 문장 매핑(portfolio.md)" 형식은 사용자가 명시적으로 선택하지 않아 이번 범위에서 제외했다.

## 검토했으나 채택하지 않은 대안
### 대안 A: 이력서 문장 매핑(portfolio.md) 형식
- 무엇: `codebot-portfolio.md`처럼 이력서 문장 → 코드 근거를 짧게 매핑
- 왜 안 썼나: 사용자가 AskUserQuestion에서 "코드 흐름 문서(flow.md)"를 명시적으로 선택. 면접/이력서 준비보다 "코드 복기"가 현재 목적.

### 대안 B: 핵심 구매/Saga만 우선 작성
- 무엇: serverA/B/C만 먼저 작성하고 user-service/gateway/frontend는 보류
- 왜 안 썼나: 사용자가 "전체(프론트엔드 포함)"를 명시적으로 선택.

### 대안 C: README.md/arch-snapshot.md에 통합 서술
- 무엇: 별도 파일 대신 기존 대형 문서에 흐름 섹션 추가
- 왜 안 썼나: README/arch-snapshot은 이미 500줄 이상이며 "설계 요약" 성격. flow.md는 "코드를 그대로 따라가는" 성격으로 목적이 다르고, 기존 3개 모듈도 이미 별도 파일로 분리되어 있어 일관성을 위해 유지.

## 기존 코드베이스 컨벤션
- flow.md 문서 구조(aiops-flow.md 기준): 상단에 1~2문장 요약 → "진입점" 표 또는 목록 → 흐름별 번호 매긴 단계 서술(클래스.메서드() 표기) → 필요 시 코드 스니펫 없이 절차만 텍스트로. Mermaid 다이어그램은 README/arch-snapshot에 이미 있으므로 flow.md에는 넣지 않는 경향(aiops-flow.md는 다이어그램 없이 텍스트 절차만 사용).
- 멀티모듈 Gradle 구조: `serverA`/`serverB`/`serverC`/`user-service`/`gateway-service`/`aiops`/`codebot`/`cs-bot`/`discovery-service`가 각각 독립 Spring Boot 앱. 패키지 루트: `promotion.serverA`, `promotion.serverB`, `promotion.serverC`, `com.example.user_service`, `com.example.gateway_service`.
- 예외 처리 패턴: 각 모듈에 `exception/GlobalExceptionHandler.java` 존재 (`.claude/skills/exception` 참고).
- 프론트: `frontend/src/api/generated/`는 orval 자동 생성 코드 — 문서화 시 "생성됨"을 명시하고 수동 코드와 구분.

## 관련 파일/위치
- serverA/src/main/java/promotion/serverA/ — Saga 오케스트레이터(구매 접수·주문·재고)
- serverB/src/main/java/promotion/serverB/ — CQRS 읽기 전용(주문 상태·재고 뷰)
- serverC/src/main/java/promotion/serverC/ — 결제 처리(PG 연동)
- user-service/src/main/java/com/example/user_service/ — 회원·JWT·결제수단
- gateway-service/src/main/java/com/example/gateway_service/ — JWT 인증 필터·Rate Limiting (파일 3개뿐, 라우팅 규칙은 application.yml에 정의)
- frontend/src/pages/OrderStatusPage.tsx — 주문 상태 폴링 화면
- docs/aiops-flow.md, docs/codebot-flow.md, docs/qa/cs-bot-flow.md — 참고할 기존 패턴 원본
- docs/arch-snapshot.md, README.md — 교차 검증 기준 문서 (이미 신뢰 가능한 것으로 확인됨, 2026-06-17 기준 최신)

## 외부 참조
없음

---

# 맥락 노트 추가: aiops-flow.md / codebot-flow.md 보강

## 왜 이 방식을 선택했는가
사용자가 aiops/codebot 코드 자체를 잘 기억하지 못한다고 밝혔고, 기존 두 문서는 실제 소스 33개 파일을 전부 대조하지 않은 상태였다(직전 작업에서는 aiops-flow.md를 60줄만 확인, codebot-flow.md는 미확인). "기존 구조 유지 + 부족한 부분만 보강"을 사용자가 명시적으로 선택 — 이미 검증된 문서 구조를 깨지 않으면서 코드 근거를 채우는 것이 목표.

## 검토했으나 채택하지 않은 대안
### 대안: 전체 재작성
- 무엇: 33개 파일을 읽은 뒤 두 문서를 처음부터 다시 구성
- 왜 안 썼나: 사용자가 "기존 구조 유지"를 명시적으로 선택. 기존 구조가 이미 실전 검증된 패턴(aiops-flow.md가 다른 신규 문서들의 원본 템플릿이었음)이라 재작성 리스크가 이득보다 큼.

## 관련 파일/위치
- aiops/src/main/java/aiops/aiops/ — 19개 파일, 패키지: agent/approval/webhook/slack/linear/router/scheduler/tools/config
- codebot/src/main/java/codebot/codebot/ — 14개 파일, 패키지: api/config/tools/agent
- docs/aiops-flow.md, docs/codebot-flow.md — 보강 대상 원본

## 외부 참조
없음
