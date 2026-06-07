# 체크리스트: AIOps 도구 추가 — getIstioMeshStatus / queryKafkaLag / 트래픽 가중치 검증

- 마지막 업데이트: 2026-06-07

## 진행 상황

- [x] 단계 1: KubernetesTools.java — getIstioMeshStatus() 추가 + proposeTrafficShift() 검증
  - [x] getIstioMeshStatus() 메서드 추가
  - [x] proposeTrafficShift() 상단 v1+v2=100 guard 추가
  - [x] 검증 통과 (`.\gradlew.bat :aiops:compileJava` — BUILD SUCCESSFUL)

- [x] 단계 2: ObservabilityTools.java — queryKafkaLag() 추가
  - [x] queryKafkaLag() 메서드 추가
  - [x] 검증 통과 (`.\gradlew.bat :aiops:compileJava` — BUILD SUCCESSFUL)

## 최종 검증
- [x] `.\gradlew.bat :aiops:compileJava` 오류 없음
- [x] 변경 파일이 KubernetesTools.java, ObservabilityTools.java 2개뿐임을 git diff로 확인
- [x] 비범위(AiOpsAgentService 시스템 프롬프트, 기타 파일) 침범 없음

## 발견 사항
- (없음)
