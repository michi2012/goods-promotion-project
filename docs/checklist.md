# 체크리스트: AIOps 추가 K8s 도구 및 Helm 롤백 구현

- 마지막 업데이트: 2026-06-05 (단계 1~6 완료)

## 진행 상황
- [x] 단계 1: Dockerfile helm 추가 + proposeScale 제거
  - [x] `.\gradlew.bat :aiops:compileJava -x test` 통과
- [x] 단계 2: proposeHpaPatch 구현
  - [ ] KubeHPAAtMaxReplicas 알람 → Slack 버튼 발송 확인
  - [ ] 승인 클릭 → kubectl patch hpa 실행 로그 확인
- [x] 단계 3: proposeHelmRollback 구현
  - [ ] 알람 전송 → Slack 버튼 발송 확인
  - [ ] 승인 클릭 → helm rollback 실행 로그 확인
- [x] 단계 4: gateway HPA 추가
  - [x] `helm template` 결과에 gateway-service-hpa HorizontalPodAutoscaler 포함 확인
- [x] 단계 5: KafkaConsumerLagHigh 알람 — 이미 존재 (threshold 500, 1m), 스킵
- [x] 단계 6: 시스템 프롬프트 업데이트
  - [ ] 각 시나리오 알람 테스트

## 최종 검증
- [ ] 모든 컴파일 통과
- [ ] proposeScale 관련 코드가 완전히 제거되었는지 확인
- [ ] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인
- [ ] git diff로 의도하지 않은 파일 변경 없는지 확인

## 발견 사항
- 없음
