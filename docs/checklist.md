# 체크리스트: @EnableJpaAuditing 분리

- 마지막 업데이트: 2026-05-21 (완료)

## 진행 상황
- [x] 단계 1: JpaAuditingConfig 생성 및 @EnableJpaAuditing 이동
  - [x] 검증 통과 (사용자 직접 확인)
  - [x] 코드리뷰 통과

## 최종 검증
- [x] OrderControllerTest 전체 통과
- [x] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인
- [x] 의도하지 않은 파일 변경이 없는지 확인

## 발견 사항
- serverA, serverB도 동일 패턴(@EnableJpaAuditing on Application class)일 가능성 있음 — 이번 범위 외
