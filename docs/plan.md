# 계획서: PromotionException log.error 전환 + 에러 테스트 컨트롤러 추가

- 작성일: 2026-05-25

## 목표
PromotionException 핸들러 로그 레벨을 log.error로 올리고, PromotionException을 강제 발생시키는 테스트 엔드포인트를 serverA에 추가한다.

## 성공 기준
- [ ] GlobalExceptionHandler의 PromotionException 핸들러가 log.error로 찍힘
- [ ] GET /api/v1/test/error/promotion 호출 시 PromotionException 응답(4xx) 반환
- [ ] 빌드 성공 (`gradlew.bat :serverA:compileJava`)

## 비범위 (Out of Scope)
- BusinessException 계열 로그 레벨 변경
- @Profile("dev") 적용
- 다른 예외 타입 테스트 엔드포인트

## 단계별 작업 계획

### 단계 1: GlobalExceptionHandler log.warn → log.error (PromotionException만)
- 변경 파일: `serverA/src/main/java/weverse/serverA/exception/GlobalExceptionHandler.java`
- 변경 내용: handlePromotionException의 log.warn을 log.error로 교체
- 검증 방법: 파일 내용 확인
- 롤백 방법: log.error → log.warn 원복
- 예상 소요: 짧음

### 단계 2: ErrorTestController 생성
- 변경 파일: `serverA/src/main/java/weverse/serverA/controller/ErrorTestController.java` (신규)
- 변경 내용: GET /api/v1/test/error/promotion → PromotionException(BAD_REQUEST) throw
- 검증 방법: `gradlew.bat :serverA:compileJava`
- 롤백 방법: 파일 삭제
- 예상 소요: 짧음

## 리스크 및 대응
- 없음 (기존 로직 변경 없음, 신규 엔드포인트 추가만)
