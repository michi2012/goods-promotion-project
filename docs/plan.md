# 계획서: cs-bot Phase 2 — Elasticsearch RAG + Metadata Filter + Structured Output 긴급도 분류

- 작성일: 2026-06-17
- 관련 이슈/티켓: 없음

## 목표
cs-bot에 Elasticsearch 기반 FAQ RAG를 추가해 정책 질문을 자동 답변하고, Structured Output으로 문의 긴급도를 분류해 Linear 이슈 priority에 반영한다.

## 성공 기준
- [ ] "환불 기간이 얼마나 돼요?" 질문 시 escalateToHuman 없이 FAQ 기반 답변 반환
- [ ] Metadata Filter로 카테고리(환불정책/배송정책/구매규칙) 별 정확한 문서 검색
- [ ] escalateToHuman 호출 시 urgency(HIGH/MEDIUM/LOW) → Linear priority 자동 매핑
- [ ] docker-compose.infra.yml에서 Elasticsearch 컨테이너 정상 기동 (`curl localhost:9200` 응답)
- [ ] helm template 렌더링 오류 없음 (`helm template ./helm/promotion-app`)

## 비범위
- Elasticsearch 클러스터 이중화 / 영속성 (emptyDir, 데모용 단일 노드)
- 임베딩 모델 교체 (Gemini 임베딩 유지)
- FAQ 문서 관리 UI / 동적 업데이트
- cs-bot 외 서비스에 RAG 적용

## 단계별 작업 계획

### 단계 1: gradle 의존성 + application.yaml ES 설정
- 변경 파일: `cs-bot/build.gradle`, `cs-bot/src/main/resources/application.yaml`
- 변경 내용: spring-ai-elasticsearch-store 의존성 추가. application.yaml에 elasticsearch uris/index-name 설정 추가
- 검증: `./gradlew :cs-bot:compileJava`
- 롤백: 의존성·설정 제거
- 예상 소요: 짧음

### 단계 2: FAQ 문서 + ElasticsearchVectorStore 설정 + FaqDocumentLoader
- 변경 파일: `cs-bot/src/main/resources/faq/*.yaml` (3개 신규), `csbot/config/ElasticsearchVectorStoreConfig.java` (신규), `csbot/faq/FaqDocumentLoader.java` (신규)
- 변경 내용: 환불정책·배송정책·구매규칙 FAQ YAML 작성. VectorStore Bean 설정. ApplicationRunner로 시작 시 FAQ 문서 임베딩 후 ES에 저장
- 검증: 앱 기동 후 `curl localhost:9200/cs-faq/_count` → count > 0
- 롤백: 신규 파일 삭제
- 예상 소요: 보통

### 단계 3: FaqSearchTools + CsChatAgentService 연동
- 변경 파일: `csbot/tools/FaqSearchTools.java` (신규), `csbot/router/CsChatAgentService.java`
- 변경 내용: `@Tool searchFaq(query, category)` — VectorStore.similaritySearch + Metadata Filter. CsChatAgentService tools에 FaqSearchTools 추가, SYSTEM_PROMPT에 FAQ 검색 지침 추가
- 검증: "환불 기간이 얼마나 돼요?" 요청 시 searchFaq 호출 로그 확인
- 롤백: FaqSearchTools 제거, CsChatAgentService 원복
- 예상 소요: 짧음

### 단계 4: Structured Output 긴급도 분류 + Linear priority 연동
- 변경 파일: `csbot/classification/CsClassification.java` (신규 record), `csbot/classification/CsClassificationService.java` (신규), `csbot/router/CsChatAgentService.java`, `csbot/linear/CsEscalationService.java`
- 변경 내용: CsClassification(urgency, category, requiresHuman) record 정의. CsClassificationService에서 chat 후 별도 classify 호출. escalateToHuman에 urgency 전달, CsEscalationService GraphQL에 priority 필드 추가
- 검증: escalateToHuman 호출 시 Linear 이슈에 priority 필드 확인
- 롤백: 신규 파일 삭제, escalateToHuman 시그니처 원복
- 예상 소요: 보통

### 단계 5: docker-compose.infra.yml Elasticsearch 추가
- 변경 파일: `docker-compose.infra.yml`
- 변경 내용: elasticsearch 8.x 단일 노드 서비스 추가 (포트 9200, security 비활성화)
- 검증: `docker compose -f docker-compose.infra.yml up elasticsearch -d` → `curl localhost:9200`
- 롤백: 서비스 블록 제거
- 예상 소요: 짧음

### 단계 6: Helm — Elasticsearch Deployment/Service + cs-bot 환경변수
- 변경 파일: `helm/promotion-app/values.yaml`, `helm/promotion-app/templates/elasticsearch/deployment.yaml` (신규), `helm/promotion-app/templates/elasticsearch/service.yaml` (신규), `helm/promotion-app/templates/cs-bot/deployment.yaml`
- 변경 내용: values.yaml에 elasticsearch 섹션 추가. Elasticsearch 단일 노드 Deployment+Service 템플릿 작성. cs-bot deployment에 ELASTICSEARCH_URIS 환경변수 추가
- 검증: `helm template ./helm/promotion-app` 오류 없음
- 롤백: 신규 파일 삭제, values.yaml 원복
- 예상 소요: 보통

## 리스크 및 대응
- Elasticsearch 기동 전 FaqDocumentLoader 실행 시 실패 → `@ConditionalOnProperty` 또는 RetryTemplate으로 재시도
- Gemini 임베딩 API 호출 실패 시 로딩 중단 → 예외 catch 후 warn 로그만 남기고 앱 기동 계속

## 의존성
- Elasticsearch 8.x 컨테이너 기동 후 cs-bot이 ELASTICSEARCH_URIS에 접근 가능해야 함
- Gemini API key (AI_API_KEY) — 이미 설정됨
