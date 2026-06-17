# 체크리스트: cs-bot Phase 2 — Elasticsearch RAG + Metadata Filter + Structured Output

- 마지막 업데이트: 2026-06-17

## 진행 상황
- [x] 단계 1: gradle 의존성 + application.yaml ES 설정
  - [x] 검증 통과 (`./gradlew :cs-bot:compileJava` → BUILD SUCCESSFUL)
- [x] 단계 2: FAQ 문서 + ElasticsearchVectorStoreConfig + FaqDocumentLoader
  - [x] 컴파일 통과. `curl localhost:9200/cs-faq/_count` 실측은 단계 5(ES 컨테이너) 이후 진행
- [x] 단계 3: FaqSearchTools + CsChatAgentService 연동
  - [x] 컴파일 통과. 실제 호출 로그는 단계 5 이후 E2E에서 확인
- [x] 단계 4: Structured Output 긴급도 분류 + Linear priority 연동
  - [x] 컴파일 통과. priority 매핑 수정(HIGH=1,MEDIUM=3,LOW=4). 실제 Linear 이슈 확인은 E2E 단계에서
- [x] 단계 5: docker-compose.yml Elasticsearch 추가 (infra.yml이 아닌 메인 파일로 변경)
  - [x] 검증 통과 (컨테이너 healthy, `curl localhost:9200` 응답 확인)
- [x] 단계 6: Helm Elasticsearch Deployment/Service + cs-bot 환경변수
  - [x] 검증 통과 (`helm template ./helm/promotion-app` exit 0)

## 최종 검증
- [x] 컴파일 통과 (`./gradlew :cs-bot:compileJava`, `bootJar`)
- [x] E2E 통과: FAQ RAG 응답 정확("환불은 언제까지" → 7일/3일 정책 반환)
- [x] E2E 통과: Elasticsearch 색인 12건 확인 (`curl localhost:9200/cs-faq/_count`)
- [x] E2E 통과: Structured Output 분류 정확 (분노 표현 → urgency=HIGH)
- [x] E2E 통과: Linear 실제 이슈(MIC-19) priority=1(Urgent) 매핑 확인
- [x] helm template 렌더링 통과
- [ ] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인
- [ ] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인

## 발견 사항 (작업 중 별도 처리 필요한 것)
- spring-ai-google-genai(Chat)와 spring-ai-google-genai-embedding(Embedding)이 별도 아티팩트 — 최초 조사 시 놓쳤다가 사용자 피드백으로 정정
- 임베딩 연결 설정은 Chat과 다른 prefix(`spring.ai.google.genai.embedding.api-key`, `...embedding.text.options.model`) 사용
- text-embedding-004가 2026-01-14 deprecated(오늘 2026-06-17 기준) → gemini-embedding-001(3072차원)로 교체
- cs-bot/Dockerfile은 build/libs/*.jar를 그대로 복사 — compileJava만으로는 이미지 미반영, bootJar 필요
- docker compose는 -f를 지정하면 override.yml 자동 병합 안 됨 — 두 파일 모두 명시 필요
