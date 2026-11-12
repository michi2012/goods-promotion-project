-- 결제 금액(원 단위)과 통화. 금액 도입 이전 결제 및 금액 없는 옛 payment-request 메시지로 생성된 결제는 NULL.
-- AFTER 위치 지정을 하지 않는다: MySQL 8.0.29 미만에서 INSTANT DDL을 막아 테이블이 재구성될 수 있다.
ALTER TABLE payments
    ADD COLUMN amount   BIGINT     NULL,
    ADD COLUMN currency VARCHAR(3) NULL;
