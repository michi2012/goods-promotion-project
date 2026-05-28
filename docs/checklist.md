# 체크리스트: FinalOrder → Payment 리네이밍

- 마지막 업데이트: 2026-05-28

## 진행 상황
- [x] 단계 1: entity/FinalOrder.java → Payment.java (클래스명 + @Table 변경)
  - [x] 파일 내 `FinalOrder` 문자열 없음 확인
- [x] 단계 2: repository/FinalOrderRepository.java → PaymentRepository.java (SQL `final_order` → `payments`)
  - [x] `final_order` 문자열 없음 확인
- [x] 단계 3: PaymentService.java import/필드 참조 변경
  - [x] `FinalOrderRepository` 문자열 없음 확인
- [x] 단계 4: PaymentServiceTest.java Mock 참조 변경
  - [x] `FinalOrderRepository` 문자열 없음 확인
- [x] 단계 5: 문서 3종 업데이트 (arch-snapshot, infra-diagram, README)
  - [x] `FinalOrder` / `final_order` 문자열 없음 확인

## 최종 검증
- [x] `serverC/src` 내 `FinalOrder` / `final_order` 잔존 없음
- [x] `README.md` 내 잔존 없음
- [x] `docs/arch-snapshot.md` 내 잔존 없음
- [x] `docs/infra-diagram.md` — 해당 없음 (원래 언급 없었음)
- [x] 비범위 침범 없음 (serverA, serverB 미변경)

## 발견 사항
- plan.md / context.md / checklist.md 자체에는 이전 이름 언급이 남아있으나, 이는 작업 기록 문서이므로 정상
