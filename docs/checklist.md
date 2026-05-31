# 체크리스트: JaCoCo 설정 및 커버리지 가이드 구축

- 마지막 업데이트: 2026-05-31

## 진행 상황
- [x] 단계 1: 루트 build.gradle에 JaCoCo 설정 추가
  - [ ] jacocoTestReport 태스크 정상 동작 확인 (사용자 직접 실행)
- [x] 단계 2: docs/coverage.md 작성
  - [x] 커맨드 및 경로 정확성 확인

## 최종 검증
- [ ] `./gradlew :serverA:jacocoTestReport` → html 리포트 생성 확인 (사용자 직접 실행)
- [ ] 기존 `./gradlew test` 동작에 영향 없음 확인
- [x] 변경 파일이 build.gradle, docs/coverage.md 2개뿐임 확인
