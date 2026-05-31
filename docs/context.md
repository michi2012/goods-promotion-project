# 맥락 노트: JaCoCo 설정 및 커버리지 가이드 구축

## 왜 이 방식을 선택했는가
JaCoCo는 커밋 훅이나 CI에 강제하지 않고, 필요할 때 수동으로 확인하는 용도로 도입.
루트 `subprojects {}` 블록에만 추가해 각 서브모듈 build.gradle 수정 없이 일괄 적용.
임계값 없이 리포트 생성만 → 현황 파악 후 나중에 기준 잡는 방식.

## 검토했으나 채택하지 않은 대안

### 대안 A: aggregate 리포트 (jacoco-report-aggregation)
- 무엇: 루트에서 4개 모듈 커버리지를 하나로 합산한 리포트 생성
- 왜 안 썼나: 추가 플러그인 및 설정 복잡도 대비 수동 확인 용도에서 오버엔지니어링

### 대안 B: pre-commit 훅에 JaCoCo 자동 실행
- 무엇: 커밋마다 test + jacocoTestReport 강제 실행
- 왜 안 썼나: 멀티모듈 전체 테스트 실행으로 커밋마다 수분 소요. 개발 흐름 방해.

### 대안 C: 각 서브모듈 build.gradle에 직접 추가
- 무엇: serverA/build.gradle, serverB/build.gradle 등에 각각 jacoco 설정
- 왜 안 썼나: 루트 subprojects 블록으로 일괄 적용하면 4개 파일 수정 불필요

## 관련 파일/위치
- `build.gradle` — subprojects 블록에 JaCoCo 플러그인 적용
- `docs/coverage.md` — 수동 실행 커맨드 가이드
- `serverA/build/reports/jacoco/test/html/index.html` — 리포트 생성 위치 (예시)
