# 계획서: FinalOrder → Payment 리네이밍

- 작성일: 2026-05-28

## 목표
serverC의 엔티티 클래스 `FinalOrder`를 `Payment`로, DB 테이블 `final_order`를 `payments`로 리네이밍한다.
Java 코드(entity/repository/service/test) 및 문서(arch-snapshot, infra-diagram, README) 전체를 일관되게 변경한다.

## 성공 기준
- [ ] `FinalOrder` 클래스명이 serverC/src 코드 어디에도 존재하지 않음
- [ ] `FinalOrderRepository` 클래스명이 코드 어디에도 존재하지 않음
- [ ] SQL 문자열 내 `final_order` 테이블명이 `payments`로 교체됨
- [ ] `@Table(name = "payments")`로 엔티티 어노테이션 반영
- [ ] 문서 3종(arch-snapshot.md, infra-diagram.md, README.md) 업데이트 완료
- [ ] PaymentServiceTest 컴파일 구조 정상 유지

## 비범위 (Out of Scope)
- DB 마이그레이션 스크립트 작성 (개발 환경 — 컨테이너 재시작으로 해결)
- serverC MySQL DB 분리(Q3) — 별도 작업
- 결제 조회 API 추가 — 별도 작업
- serverA, serverB 코드 변경

## 단계별 작업 계획

### 단계 1: entity/FinalOrder.java → Payment.java
- 변경 파일: `serverC/src/main/java/weverse/serverC/entity/FinalOrder.java` (파일명 + 내용)
- 변경 내용: 클래스명 `FinalOrder` → `Payment`, `@Table(name = "final_order")` → `@Table(name = "payments")`, Builder 생성자 클래스명 변경
- 검증 방법: 파일 내 `FinalOrder` 문자열 잔존 여부 확인
- 롤백 방법: 파일명 및 내용 원복
- 예상 소요: 짧음

### 단계 2: repository/FinalOrderRepository.java → PaymentRepository.java
- 변경 파일: `serverC/src/main/java/weverse/serverC/repository/FinalOrderRepository.java` (파일명 + 내용)
- 변경 내용: 클래스명 변경 + SQL 문자열 내 `final_order` → `payments` 3곳 교체
- 검증 방법: `final_order` 문자열 잔존 여부 확인
- 롤백 방법: 파일명 및 내용 원복
- 예상 소요: 짧음

### 단계 3: PaymentService.java — import/필드 참조 변경
- 변경 파일: `serverC/src/main/java/weverse/serverC/service/PaymentService.java`
- 변경 내용: `import FinalOrderRepository` → `PaymentRepository`, 필드 타입 및 변수명 `finalOrderRepository` → `paymentRepository`
- 검증 방법: `FinalOrderRepository` 문자열 잔존 여부 확인
- 롤백 방법: 원복
- 예상 소요: 짧음

### 단계 4: PaymentServiceTest.java — Mock 참조 변경
- 변경 파일: `serverC/src/test/java/weverse/serverC/service/PaymentServiceTest.java`
- 변경 내용: `FinalOrderRepository` Mock 선언 → `PaymentRepository`, `finalOrderRepository` → `paymentRepository`
- 검증 방법: `FinalOrderRepository` 문자열 잔존 여부 확인
- 롤백 방법: 원복
- 예상 소요: 짧음

### 단계 5: 문서 3종 업데이트
- 변경 파일: `docs/arch-snapshot.md`, `docs/infra-diagram.md`, `README.md`
- 변경 내용: `FinalOrder` → `Payment`, `final_order` → `payments` 언급 교체
- 검증 방법: 각 파일에서 `FinalOrder` / `final_order` 잔존 여부 확인
- 롤백 방법: 원복
- 예상 소요: 짧음

## 리스크 및 대응
- 기존 Docker 컨테이너에 `final_order` 테이블이 남아 스키마 불일치 → 작업 후 컨테이너 재시작 안내
- raw JDBC SQL 교체 누락 → 단계 2 완료 후 `final_order` grep으로 확인

## 의존성
- serverC raw JDBC SQL이 테이블명에 직접 의존 (JPA DDL 이외 별도 ORM 없음)
- 테스트는 Mock 기반이므로 DB 의존 없음
