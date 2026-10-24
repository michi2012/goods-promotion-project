# 체크리스트: 테스트 실행 정책 변경 + skill 문서 교정

- 마지막 업데이트: 2026-10-24 00:00

## 진행 상황
- [x] 단계 1: CLAUDE.md 테스트 분업 원칙 재작성
  - [x] 검증 통과 (grep "실행은 사용자|작성은 Claude" 결과 없음)
  - [ ] 코드리뷰 통과 (사용자 확인)
- [x] 단계 2: testing/SKILL.md(백엔드) 자가점검 수정
  - [x] 검증 통과
  - [ ] 코드리뷰 통과
- [x] 단계 3: frontend-testing/SKILL.md 실행 정책(4·5번) 재작성
  - [x] 검증 통과
  - [ ] 코드리뷰 통과
- [x] 단계 4: frontend-design-feedback/SKILL.md 교정
  - [x] 검증 통과 (grep: `5173`, `src/pages/` 미존재)
  - [ ] 코드리뷰 통과
- [x] 단계 5: frontend/SKILL.md 교정
  - [x] 검증 통과
  - [ ] 코드리뷰 통과
- [x] 단계 6: client.ts 환경변수 분리
  - [x] 검증 통과 (`tsc --noEmit` exit 0, `npm run build` exit 0)
  - [ ] 코드리뷰 통과
- [x] 단계 7: frontend-testing/SKILL.md MSW/시각회귀 보강
  - [x] 검증 통과
  - [ ] 코드리뷰 통과

## 최종 검증
- [ ] 각 skill의 섹션 번호 참조가 실제 섹션 번호와 일치
- [ ] `npx tsc --noEmit`, `npm run build` 통과
- [ ] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인
- [ ] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인

## 발견 사항 (작업 중 별도 처리 필요한 것)
- (작업 진행하며 추가)
