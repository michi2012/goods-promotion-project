# 맥락 노트: PromotionException log.error 전환 + 에러 테스트 컨트롤러 추가

## 왜 이 방식을 선택했는가
- AsyncAppender의 discardingThreshold=20 설정으로 인해 큐 80% 초과 시 WARN 이하 로그가 폐기됨
- k6 부하 테스트(5000 VU) 환경에서 WARN 로그 유실 확인
- ERROR 레벨은 discardingThreshold 대상에서 제외되어 보존 보장
- PromotionException만 error 대상(사용자 결정): BusinessException 계열은 warn 유지

## 검토했으나 채택하지 않은 대안
### discardingThreshold=0
- 무엇: 자동 폐기 비활성화
- 왜 안 썼나: 큐 꽉 찰 경우 애플리케이션 스레드 블로킹 가능성

## 기존 코드베이스 컨벤션
- 컨트롤러 위치: serverA/src/main/java/weverse/serverA/controller/
- 예외 처리: GlobalExceptionHandler (@RestControllerAdvice)
- PromotionException: RuntimeException 직접 상속, HttpStatus 필드 보유

## 관련 파일/위치
- `serverA/.../exception/GlobalExceptionHandler.java` — 예외 핸들러
- `serverA/.../exception/PromotionException.java` — 대상 예외 클래스
- `serverA/.../controller/ErrorTestController.java` — 신규 테스트 컨트롤러
