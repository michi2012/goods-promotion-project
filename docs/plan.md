# 계획서: JaCoCo 설정 및 커버리지 가이드 구축

- 작성일: 2026-05-31

## 목표
루트 `build.gradle`의 `subprojects {}` 블록에 JaCoCo 플러그인을 추가하고,
수동 커버리지 확인 시 사용할 커맨드 가이드를 `docs/coverage.md`에 정리한다.

## 성공 기준
- [ ] `./gradlew :serverA:test :serverA:jacocoTestReport` 실행 시 `serverA/build/reports/jacoco/test/html/index.html` 생성됨
- [ ] 4개 모듈(serverA, serverB, serverC, mcp) 모두 동일하게 동작
- [ ] `docs/coverage.md`에 실행 커맨드와 리포트 경로가 명확히 기술됨
- [ ] 기존 `gradlew test` 동작에 영향 없음

## 비범위 (Out of Scope)
- 최소 커버리지 임계값 설정 없음
- aggregate 리포트 (모듈 통합) 없음
- CI/CD 연동 없음
- 각 서브모듈 `build.gradle` 직접 수정 없음

## 단계별 작업 계획

### 단계 1: 루트 build.gradle에 JaCoCo 설정 추가
- 변경 파일: `build.gradle`
- 변경 내용: `subprojects {}` 블록에 `apply plugin: 'jacoco'` 및 `jacocoTestReport` 태스크 설정 추가
- 검증 방법: `./gradlew :serverA:jacocoTestReport` 실행 후 html 리포트 존재 확인
- 예상 소요: 짧음

### 단계 2: docs/coverage.md 작성
- 변경 파일: `docs/coverage.md` (신규)
- 변경 내용: 모듈별/전체 실행 커맨드, 리포트 경로, 사용 시점 가이드
- 검증 방법: 파일 내용 확인
- 예상 소요: 짧음

## 리스크 및 대응
- 리스크: 기존 test 태스크와 jacocoTestReport 순서 의존성
- 대응: `jacocoTestReport.dependsOn(test)` 명시로 해결
