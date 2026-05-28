# 체크리스트: serverC 결제 조회 API 추가

- 마지막 업데이트: 2026-05-28

## 진행 상황
- [x] 단계 1: dto/PaymentResponse.java 신규 생성
- [x] 단계 2: repository/PaymentRepository.java 조회 메서드 추가
- [x] 단계 3: service/PaymentService.java 조회 메서드 추가
- [x] 단계 4: exception/PaymentNotFoundException.java 신규 + GlobalExceptionHandler 수정
- [x] 단계 5: controller/PaymentController.java 신규 생성
- [x] 단계 6: docs/arch-snapshot.md 업데이트

## 최종 검증 (api 스킬 자가점검)
- [x] Entity 직접 노출 없음 (PaymentResponse record 사용)
- [x] 응답 DTO에 PII 없음 (email, phone, address 미포함 확인)
- [x] Controller에 @Transactional 없음
- [x] 목록 API 페이징 적용 (page/size, LIMIT/OFFSET)
- [x] HTTP 상태 코드: 200 OK, 404 Not Found

## 발견 사항
- (없음)
