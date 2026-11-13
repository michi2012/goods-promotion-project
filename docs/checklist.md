# 체크리스트: 필수 체크 대비 CI result job + main 브랜치 보호 (BL-019)

- 마지막 업데이트: 2026-11-13

## [진행 중] BL-019

### 진행 상황
- [x] 단계 1: backend-ci 변경 감지·result job
  - [x] 검증 통과 (actionlint 1.7.7, exit 0)
- [x] 단계 2: frontend-ci 같은 구조
  - [x] 검증 통과 (actionlint 1.7.7, exit 0)
- [x] 단계 3: 문서 갱신 (backlog BL-023 추가, design-notes 섹션 추가)
- [ ] 단계 4: push + PR → 실제 CI 실행 확인 (승인 후)
  - [ ] test 9개·check·result 2개 성공 (`gh pr checks`)
- [ ] 단계 5: 머지 후 main 보호 적용 (명령 재확인 후)
  - [ ] 설정 조회로 확인
- [ ] 단계 6: 문서 전용 PR로 skip 동작 검증
  - [ ] 테스트 skipped, result 성공, mergeStateStatus CLEAN

### 최종 검증
- [ ] 성공 기준 5개 확인
- [ ] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인 (`ci-cd.yaml` 미변경)
- [ ] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인

## 발견 사항 (작업 중 별도 처리 필요한 것)
- ~~`ci-cd.yaml` dev 흔적~~ → 백로그 BL-023으로 이전 완료
- 계획 변경: 부정 패턴은 extglob 대신 paths-filter `predicate-quantifier: some-with-excludes` 사용 (v4 기능, GitHub `paths`와 같은 의미)
