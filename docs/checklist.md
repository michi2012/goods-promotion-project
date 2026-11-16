# 체크리스트: Kafka DLT 발행 파티션 고정 해제 (BL-037)

- 마지막 업데이트: 2026-11-16

## [진행 중] BL-037

### 진행 상황
- [x] 단계 1: DLT 대상 파티션 -1 + 서버별 테스트
  - [x] 검증 통과 (서버별 `*KafkaConsumerConfigTest` → serverA 1개·serverB 1개·serverC 4개 통과)
- [x] 단계 2: 빌드 + 로컬 E2E 회귀
  - [x] 검증 통과 (`:serverA:build :serverB:build :serverC:build` 6m 11s — serverA 93·serverB 16·serverC 80 테스트 통과(결과 전부 이번 실행분), server-a/b/c 이미지 재빌드 후 128초 만에 UP, `backend-e2e/ npm test` 2 passed)
- [x] 단계 3: 문서
  - [x] 검증 통과 (육안 — 백로그 BL-037 진행 중 등록, 3종 문서)

### 최종 검증
- [x] 세 서버 테스트 전체 통과
- [x] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인 (DLT 토픽·DLT 리스너 팩토리 미변경)
- [x] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인 (설정 3·테스트 3·문서 4)
- [x] 발견 사항을 백로그로 이전 (발견 사항 없음)
- [ ] (위험 변경 — 이벤트) PR 생성 후 `/pr-review` 1회 — [필수] 반영·재리뷰 (`.claude/skills/pr-review`)

### 발견 사항 (작업 중 별도 처리 필요한 것)
- (없음)
