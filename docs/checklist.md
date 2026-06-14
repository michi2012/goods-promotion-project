# 체크리스트: Istio 카나리(v1/v2) 에러율 기반 트래픽 격리 — 인프라 기반 마련

- 마지막 업데이트: 2026-06-13

## 진행 상황
- [x] 단계 1: server-a/b/c Service·Deployment에 카나리 공통 라벨(`istio-canary-group`) 적용
  - [x] 검증 통과 (`helm template helm/promotion-app` — Service selector / v1 Deployment selector 확인)
  - [ ] 코드리뷰 통과
- [x] 단계 2: values.yaml에 serverA/B/C canary 옵션 추가
  - [x] 검증 통과 (`helm template helm/promotion-app` 기본값 렌더링)
  - [ ] 코드리뷰 통과
- [x] 단계 3: server-a/b/c v2(canary) Deployment 템플릿 신규 추가
  - [x] 검증 통과 (`helm template helm/promotion-app --set serverA.canary.enabled=true --set serverB.canary.enabled=true --set serverC.canary.enabled=true`)
  - [ ] 코드리뷰 통과
- [x] 단계 4: Prometheus istio-waypoint 스크랩 job 추가
  - [x] 검증 통과 (`helm template helm/promotion-monitoring`)
  - [ ] 코드리뷰 통과
- [x] 단계 5: aiops 시스템 프롬프트에 버전별 에러율 비교 가이드 추가
  - [x] 검증 통과 (`.\gradlew.bat :aiops:test`)
  - [ ] 코드리뷰 통과
- [x] 단계 6: 최종 통합 검증 및 문서화
  - [x] 검증 통과 (helm template 전체 + git diff --stat)

## 최종 검증
- [x] `helm template helm/promotion-app` (canary 비활성/활성 양쪽) 통과
- [x] `helm template helm/promotion-monitoring` 통과
- [x] `.\gradlew.bat :aiops:test` 통과
- [x] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인 — 9개 변경 파일 + 3개 신규 파일 모두 6단계 계획 범위 내
- [x] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인

## 발견 사항 (작업 중 별도 처리 필요한 것)
- waypoint pod 라벨/포트(15020, /stats/prometheus)는 추정 — EKS 배포 후 Prometheus targets에서 실제 라벨 확인 필요
- Service selector 전환(`app:` → `istio-canary-group:`) 적용 시, EKS에서는 "v1 pod template 라벨 추가 rollout 완료 → Service selector 전환" 순서로 적용해야 무중단 (design-notes.md에 기록 예정)
