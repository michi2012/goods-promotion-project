# 체크리스트: codebot Pyroscope 핫스팟 도구 추가 + 단일파일 코드수정/PR 워크플로우 (묶음 B)

- 마지막 업데이트: 2026-06-13

## 진행 상황
- [x] 단계 1: codebot Pyroscope 핫스팟 도구 추가 (RestClientConfig, application.yaml ×2, ObservabilityTools, ObservabilityToolsTest)
  - [x] 검증 통과 (`.\gradlew.bat :codebot:test`)
  - [ ] 코드리뷰 통과
- [x] 단계 2: CodebotAgentService 조사 원칙에 Pyroscope 반영
  - [x] 검증 통과 (`.\gradlew.bat :codebot:test`)
  - [ ] 코드리뷰 통과
- [x] 단계 3: PullRequestTools 신규 클래스 — 단일파일 수정 + PR 생성/추가커밋
  - [x] 검증 통과 (`.\gradlew.bat :codebot:test`)
  - [ ] 코드리뷰 통과
- [x] 단계 4: CodebotAgentService에 PullRequestTools 통합 + "코드 수정" 프롬프트 섹션
  - [x] 검증 통과 (`.\gradlew.bat :codebot:build`)
  - [ ] 코드리뷰 통과
- [x] 단계 5: createFixPullRequest 보호 경로(.github/, .env) 가드 추가
  - [x] 검증 통과 (`.\gradlew.bat :codebot:test`)
  - [ ] 코드리뷰 통과

## 최종 검증
- [x] `.\gradlew.bat :codebot:build` 통과
- [x] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인
- [x] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인
- [x] 민감 정보(GITHUB_TOKEN 등)가 코드/로그/응답에 노출되지 않았는지 확인
- [ ] (사용자 직접) Slack에서 "고쳐서 PR 올려줘" 1회 트리거 — 실제 GitHub 브랜치/PR 생성 또는 기존 PR 추가 커밋 동작 확인

## 발견 사항 (작업 중 별도 처리 필요한 것)
- (이전 묶음에서 발견, 비범위) `serverA:test` Testcontainers 빌드 실패 — 이번 plan과 무관, 별도 처리 필요

---

# 체크리스트: 데드락 의심 롤링 재시작 시 스레드 덤프 자동 확보 (묶음 C)

- 마지막 업데이트: 2026-06-13

## 진행 상황
- [x] 단계 1: extractBlockedThreads 파싱/요약 헬퍼 추가 + 단위 테스트
  - [x] 검증 통과 (`.\gradlew.bat :aiops:test`)
  - [ ] 코드리뷰 통과
- [x] 단계 2: extractThreadDumpSummary(kubectl 연계) + proposeRolloutRestart/buildRestartBlocks 통합
  - [x] 검증 통과 (`.\gradlew.bat :aiops:test` — BUILD SUCCESSFUL, 컴파일 포함)
  - [ ] 코드리뷰 통과

## 최종 검증
- [x] `.\gradlew.bat :aiops:test` 통과 (BUILD SUCCESSFUL, KubernetesToolsTest 3/3)
- [x] 변경 사항이 plan.md(묶음 C)의 "비범위"를 침범하지 않았는지 확인 — KubernetesTools.java(+테스트) 외 변경 없음
- [x] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인 — `aiops/.../KubernetesTools.java`, `aiops/.../KubernetesToolsTest.java`(신규)만 이번 작업 범위
- [x] 민감 정보가 스레드 덤프 요약/응답에 노출되지 않는지 확인 — extractBlockedThreads는 스레드명/상태/스택트레이스(클래스·메서드명)만 추출, 변수 값 미포함

## 발견 사항 (작업 중 별도 처리 필요한 것)
- (없음)

---

# 체크리스트: 자동조치 완료 후 Linear 감사 티켓 자동 생성 (Part 1)

- 마지막 업데이트: 2026-06-13

## 진행 상황
- [x] 단계 1: aiops Linear 클라이언트 설정 추가 (RestClientConfig, application.yaml, test/resources/application.yaml)
  - [x] 검증 통과 (`.\gradlew.bat :aiops:test` — AiopsApplicationTests 전체 컨텍스트 로딩 포함)
  - [ ] 코드리뷰 통과
- [x] 단계 2: LinearAuditService 신규 클래스 + 단위 테스트
  - [x] 검증 통과 (`.\gradlew.bat :aiops:test` — LinearAuditServiceTest 4/4)
  - [ ] 코드리뷰 통과
- [x] 단계 3: SlackInteractiveController 통합 (6개 분기 + sendResultWithAudit 헬퍼)
  - [x] 검증 통과 (`.\gradlew.bat :aiops:test` — BUILD SUCCESSFUL, 컴파일 포함)
  - [ ] 코드리뷰 통과
- [x] 단계 4: docker-compose.yml 설정 추가 (LINEAR_API_KEY/LINEAR_TEAM_ID)
  - [x] 육안 확인

## 최종 검증
- [x] `.\gradlew.bat :aiops:test` 통과 (BUILD SUCCESSFUL)
- [x] 변경 사항이 plan.md(Part 1)의 "비범위"를 침범하지 않았는지 확인 — HPA/카나리/Jira/라벨 자동분류 미포함
- [x] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인 — Part 1 범위 파일(RestClientConfig, application.yaml ×2, SlackInteractiveController, LinearAuditService(+테스트), docker-compose.yml, docs)만 변경, 묶음 B/C 파일은 무변경
- [x] 민감 정보(LINEAR_API_KEY 등)가 코드/로그/응답/감사 티켓 본문에 노출되지 않는지 확인 — `${LINEAR_API_KEY:}` env var 참조만 사용, 로그/응답/티켓 본문에 actionType·params·reason·executionResult만 포함

## 발견 사항 (작업 중 별도 처리 필요한 것)
- 계획서는 감사 티켓 결과를 "후속 Slack 메시지"로 전송한다고 적었으나, `sendToResponseUrl`의 `replace_original: true` 때문에 실행 결과와 감사 티켓 결과를 하나의 메시지로 합쳐 단일 호출로 전송하도록 구현 (`sendResultWithAudit` 헬퍼). docs/context.md에 사유 기록함.
