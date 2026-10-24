# 체크리스트: 모듈별 코드 흐름 문서화 (flow.md 4종 신규 작성)

- 마지막 업데이트: 2026-09-11 00:00

## 진행 상황
- [x] 단계 1: docs/purchase-saga-flow.md 작성 (serverA/B/C)
  - [x] 검증 통과 (클래스/메서드/토픽명 grep 교차 확인 — 8개 클래스, 메트릭명 전수 확인)
  - [ ] 코드리뷰 통과 (사용자 확인)
- [x] 단계 2: docs/user-service-flow.md 작성
  - [x] 검증 통과 (grep 6개 파일 교차 확인)
  - [ ] 코드리뷰 통과
- [x] 단계 3: docs/gateway-service-flow.md 작성
  - [x] 검증 통과 (라우팅 yml 실물 대조 — k8s 프로필 cs-bot 라우트 누락 발견, "발견 사항"에 기록)
  - [ ] 코드리뷰 통과
- [x] 단계 4: docs/frontend-flow.md 작성
  - [x] 검증 통과 (README 폴링 정책과 코드 대조 — **불일치 발견**, 아래 발견 사항 참고)
  - [ ] 코드리뷰 통과

## 최종 검증
- [x] 4개 문서 모두 생성 확인 (purchase-saga-flow.md, user-service-flow.md, gateway-service-flow.md, frontend-flow.md)
- [x] 각 문서 언급 클래스/메서드/엔드포인트가 실제 소스와 일치 (grep 교차 확인 완료)
- [x] README.md/arch-snapshot.md와 내용 모순 없음 확인 — **모순 3건 발견, 아래 발견 사항 참고 (기존 문서는 수정하지 않음)**
- [x] plan.md의 "비범위"를 침범하지 않았는지 확인 (portfolio.md류 미생성, 기존 문서 미수정, 코드 변경 없음)
- [x] git status로 신규 파일 4개(docs/*.md) + plan/context/checklist 수정 외 의도치 않은 변경 없음 확인

## 발견 사항 (작업 중 별도 처리 필요한 것)
- `docs/arch-snapshot.md`의 "스케줄러 및 배치 잡" 표: `SagaTimeoutScheduler`를 `fixedDelay=60초/10분 초과`로 기재하고 있으나 실제 코드는 `30초/3분`(README.md 서술과는 일치). arch-snapshot.md 수정은 이번 범위 밖.
- `gateway-service/src/main/resources/application-k8s.yml`의 `spring.cloud.gateway.routes`에 **cs-bot 라우트가 없음**(local `application.yml`에만 존재). Spring Boot는 프로필별 리스트 프로퍼티를 병합하지 않고 교체하므로 k8s 환경에서 게이트웨이를 통한 cs-bot 접근이 불가능할 수 있음. 실제 k8s 배포 시 확인 필요.
- `user-service`에 `ROLE_DRIVER`(내부 전용, `InternalUserController.createInternalUser()`)가 존재하나 `arch-snapshot.md`의 User 엔티티 설명은 "ROLE_USER / ROLE_ADMIN"만 언급.
- **(중요) README.md가 서술하는 프론트엔드 폴링 정책(초기 대기 2s, Exponential Backoff+Jitter 2→4→8→16→32s 최대 6회, 60s 타임아웃, PAID/FAILED/EXPIRED 상태별 분기)이 실제 `frontend/src/pages/OrderStatusPage.tsx` 코드에 전혀 없음. 현재 프론트는 "조회" 버튼 클릭 시 1회 GET 요청만 보내는 정적 UI. `load-test.js`/`load-test2.js`에서도 해당 로직 없음 — 어디에도 구현되어 있지 않은 것으로 보임. README 수정 여부 사용자 확인 필요.

## 추가 작업: aiops-flow.md / codebot-flow.md 보강
- [x] 단계 1: docs/aiops-flow.md 보강 (19개 파일 정독 완료)
  - [x] 검증 통과 (grep 8개 파일 교차 확인) — 스레드 덤프 자동추출, 수동 curl 승인 경로(/action/approve), Pod 개별 재시작, ChatMemory 20메시지, 진입점 실제 4개(기존 문서는 2개로 서술) 등 보강
  - [ ] 코드리뷰 통과
- [x] 단계 2: docs/codebot-flow.md 보강 (14개 파일 정독 완료)
  - [x] 검증 통과 (grep 5개 파일 교차 확인) — **중요 정정**: "aiops와 동일한 도구 세트"라는 기존 서술이 부정확함을 발견(codebot은 aiops 대비 훨씬 적은 도구를 가진 별도 구현체이며 K8s propose/실행 도구가 전혀 없음). PullRequestTools의 브랜치 멱등 처리·보호경로·자동 assignee 등 보강
  - [ ] 코드리뷰 통과

### 추가 최종 검증
- [x] 두 문서 모두 기존 구조(섹션 순서) 유지 확인 (aiops는 도구 상세 섹션 신설로 5~8번 재번호, codebot은 섹션 순서 그대로 내용만 보강)
- [x] 추가/수정된 내용의 클래스명·메서드명 grep 교차 확인 (aiops 8개 파일, codebot 5개 파일)
- [x] git diff로 의도치 않은 변경 없는지 확인

### 추가 발견 사항
- **aiops-flow.md**: 상단 요약이 "진입점 2개"라 서술했으나 실제로는 4개(`/slack/interactive`, `/action/approve/{id}` 승인 콜백 누락). 정정 완료.
- **aiops-flow.md**: `proposeRolloutRestart`가 Slack 승인 요청 전 `kill -3 1`로 대상 Pod의 스레드 덤프를 자동 추출해 BLOCKED 스레드만 첨부하는 기능이 기존 문서에 전혀 없었음. 추가 완료.
- **aiops-flow.md**: `ObservabilityTools.proposeAction()` + `ActionApprovalController`(`POST /action/approve/{id}`)로 이어지는, Slack 버튼을 거치지 않는 별도의 수동 curl 승인 경로가 기존 문서에 없었음(이 경로는 `execute*`를 호출하지 않아 실제 조치가 실행되지 않음). 추가 완료.
- **codebot-flow.md**: "ObservabilityTools/KubernetesTools가 aiops와 동일한 도구 세트"라는 기존 서술이 부정확 — codebot은 K8s 조회 도구 1개(`getClusterStatus`, Pod+Deployment만)뿐이고 propose/실행 계열 도구가 전무함. 정정 완료.
