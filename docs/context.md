# 맥락 노트: cs-bot Phase 2 — Elasticsearch RAG + Metadata Filter + Structured Output

## 왜 이 방식을 선택했는가
- cs-bot Phase 1은 실시간 데이터 조회(주문/결제/DLT) 중심이라 FAQ 정책 질문은 전부 escalateToHuman으로 빠졌음
- "환불 기간", "배송 정책" 같은 정책 질문은 키워드가 다양해(환불=반품=취소) 의미 기반 검색이 필요 → VectorStore 채택
- Metadata Filter: 카테고리 혼용 방지. "환불 정책 알려줘" → 배송 정책 문서가 상위 노출되는 현상 차단
- Structured Output 긴급도 분류: tool calling 후 별도 2단계 호출. Spring AI에서 tool calling + entity() 동시 사용은 불안정하므로 분리. aiops IntentClassifierService의 기존 패턴과 일치.

## 검토했으나 채택하지 않은 대안
### PgVector
- 무엇: 기존 MySQL 대신 PostgreSQL+pgvector 사용
- 왜 안 썼나: MySQL을 이미 쓰고 있고 Elasticsearch가 포트폴리오 인지도가 높음

### Chroma
- 무엇: 경량 벡터 DB
- 왜 안 썼나: 인지도 낮음. Elasticsearch가 엔터프라이즈 환경에서 더 현실적 선택임을 어필 가능

### tool calling + entity() 단일 호출
- 무엇: CsChatAgentService에서 tools + .entity(CsClassification.class) 동시 사용
- 왜 안 썼나: Spring AI 1.1.7에서 모델에 따라 불안정. 2단계 분리가 더 명확하고 기존 IntentClassifierService 패턴과 일치

## 기존 코드베이스 컨벤션
- Tool 클래스: `@Tool` 메서드, 반환 String, `@ToolParam`으로 파라미터 설명
- Agent 서비스: ChatClient.Builder 주입, `.tools(...)` 체이닝
- 에러 처리: try-catch → log.warn → 에러 설명 문자열 반환 (CsBotTools 패턴)
- Structured Output: `.call().entity(Record.class)` — IntentClassifierService 참조

## 관련 파일/위치
- `cs-bot/src/main/java/csbot/csbot/tools/CsBotTools.java` — 기존 7개 Tool
- `cs-bot/src/main/java/csbot/csbot/router/CsChatAgentService.java` — ChatClient 조합 지점
- `cs-bot/src/main/java/csbot/csbot/linear/CsEscalationService.java` — Linear GraphQL 호출
- `aiops/.../router/IntentClassifierService.java` — Structured Output 패턴 레퍼런스
