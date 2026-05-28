# 맥락 노트: FinalOrder → Payment 리네이밍

## 왜 이 방식을 선택했는가
serverC의 역할이 "결제 처리(PG 연동)"인데 엔티티명이 `FinalOrder`여서 serverA의 `Order`와 도메인 경계가 모호했다.
`Payment`로 바꾸면 serverC가 결제 도메인을 소유한다는 의도가 명확해진다.
테이블명도 `payments`로 함께 변경해 Java-DB 네이밍 일관성을 유지한다.

## 검토했으나 채택하지 않은 대안

### 대안 A: 클래스명만 변경, 테이블명 유지 (`final_order`)
- 무엇: Java 코드만 바꾸고 DB 테이블명은 그대로
- 왜 안 썼나: Java는 `Payment`인데 DB는 `final_order`로 불일치 — 미래 개발자 혼란 유발

### 대안 B: `PaymentRecord`로 명명
- 무엇: 이력 성격을 강조한 이름
- 왜 안 썼나: 과도한 명세. `Payment`가 더 단순하고 도메인 언어와 일치

## 기존 코드베이스 컨벤션
- 디렉토리 구조: `entity/`, `repository/`, `service/` 레이어 분리
- 명명 규칙: 클래스명 PascalCase, 테이블명 snake_case 복수형 (`payments`)
- 리포지토리: JPA가 아닌 **raw JDBC (`JdbcTemplate`)** 사용 — SQL 문자열에 테이블명 하드코딩

## 관련 파일/위치
- `serverC/src/main/java/weverse/serverC/entity/FinalOrder.java` — 리네이밍 대상 엔티티
- `serverC/src/main/java/weverse/serverC/repository/FinalOrderRepository.java` — raw JDBC, SQL 문자열 교체 필요
- `serverC/src/main/java/weverse/serverC/service/PaymentService.java` — 필드 참조만 변경
- `serverC/src/test/java/weverse/serverC/service/PaymentServiceTest.java` — Mock 타입만 변경
