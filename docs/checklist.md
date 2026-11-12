# 체크리스트: K8s 환경 DiscoveryClient 서비스 조회 복구

- 마지막 업데이트: 2026-11-12

## [완료] serverA
- [x] `application-k8s.yml`에 serverC 정적 등록
  - [x] 검증 통과 (`gradlew :serverA:test --tests K8sDiscoveryConfigTest --tests PaymentQueryClientTest` — 5건 통과)

## [완료] cs-bot / aiops

### 진행 상황
- [x] 단계 1: cs-bot `application-k8s.yml` 신규 + 테스트
  - [x] 검증 통과 (`gradlew :cs-bot:test --tests "csbot.csbot.config.K8sDiscoveryConfigTest"` — 5건 통과)
- [x] 단계 2: aiops `application-k8s.yml` 수정 + 테스트
  - [x] 검증 통과 (`gradlew :aiops:test --tests "aiops.aiops.config.K8sDiscoveryConfigTest"` — 1건 통과)
- [x] 단계 3: `docs/arch-snapshot.md` 경고 문구 교체

### 최종 검증
- [x] 추가한 테스트 모두 통과 (serverA 5 / cs-bot 5 / aiops 1)
- [x] Java 프로덕션 코드 변경 없음
- [x] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인
- [x] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인

## 발견 사항 (작업 중 별도 처리 필요한 것)
- Helm Deployment들의 `EUREKA_DEFAULT_ZONE` 환경변수는 K8s에서 미사용 (비범위)
- 실제 K8s DNS 연결은 로컬 테스트로 검증 불가 — kind 검증 또는 "EKS 배포 후 검증 필요" 명시 결정 필요
