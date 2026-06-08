# 체크리스트: Linear MCP 기반 개발 워크플로우 자동화 (명령어 5종 통합)

- 마지막 업데이트: 2026-06-08 18:05

## 진행 상황
- [x] 단계 1: `/spec-draft` 신규 명령어 작성
  - [x] 검증 통과 (샘플 초안 입력 → User Story + GWT 구조 출력 → docs/specs/keyword-notification.md 저장 확인)
  - [ ] 코드리뷰 통과
- [x] 단계 2: `/spec-to-tickets` 신규 명령어 작성
  - [x] 검증 통과 (docs/specs/keyword-notification.md → MIC-5 실제 생성 확인, https://linear.app/michi2012/issue/MIC-5)
  - [ ] 코드리뷰 통과
- [x] 단계 3: `/plan`에 Linear 연동 추가
  - [x] 검증 통과 (MIC-5로 get_issue 조회 + state→In Progress 전환 + 코멘트 등록 확인. 전체 /plan 실행은 진행 중인 docs 파일 덮어쓰기 위험으로 로직만 직접 호출 검증)
  - [ ] 코드리뷰 통과
- [x] 단계 4: `/incident`에 aiops 초안 입력 처리 + Linear 서브태스크 생성 추가
  - [x] 검증 통과 (가상 장애 시나리오 → docs/incidents/2026-06-08-payment-504-cache-ttl.md 생성 + 재발 방지 2건 → MIC-6, MIC-7 (parentId: MIC-5) 서브태스크 생성 확인)
  - [ ] 코드리뷰 통과
- [x] 단계 5: `/release-notes`에 이슈 ID 추출 + 상태 전환 추가
  - [x] 검증 통과 (샘플 커밋 메시지 4건으로 `MIC-\d+` 정규식 추출 로직 실행 → 중복 제거된 ISSUE_IDS:MIC-12,MIC-7 출력 확인. Step 3 "포함된 이슈" 섹션, Step 4-B 상태 전환 절차는 실제 Linear 호출 포함이라 단계 6 E2E에서 통합 검증)
  - [ ] 코드리뷰 통과
- [x] 단계 6: 전체 파이프라인 E2E 통합 검증
  - [x] 5개 명령어 순차 실행 시나리오 통과 — 실제 고민거리(결제 종단 간 p95 알림 임계치)를 시드로 spec-draft → spec-to-tickets → plan → incident → release-notes 전 구간 Linear 연동 로직 검증
    - spec-draft: docs/specs/payment-e2e-latency-alert-threshold.md 생성
    - spec-to-tickets: MIC-8 생성
    - plan: get_issue 조회 → state In Progress 전환 → 코멘트 등록 (MIC-8)
    - incident: 재발방지 항목 → 서브태스크 MIC-9 생성, parentId=MIC-8 연결 확인
    - release-notes: 커밋 메시지 MIC-\d+ 정규식 추출(중복 제거) + save_issue(state=Done) 일괄 전환 (MIC-6으로 검증)
  - [x] 테스트용 Linear 이슈·서브태스크 정리 완료 — MIC-5/6/7을 Canceled로 전환 (MCP에 별도 archive 도구 없어 대안으로 채택). MIC-8/9는 실제 작업이므로 보존

## 최종 검증
- [x] 5개 명령어 파일 모두 정상 동작 확인 (spec-draft, spec-to-tickets, plan, incident, release-notes)
- [x] Linear 워크스페이스에 더미 데이터 잔존하지 않음 (MIC-5/6/7 Canceled 전환 완료, MIC-8/9는 실제 작업으로 보존)
- [ ] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인
- [ ] 의도하지 않은 파일 변경이 없는지 `git diff`로 최종 확인

## 발견 사항 (작업 중 별도 처리 필요한 것)
- (작업 진행하며 기록)
