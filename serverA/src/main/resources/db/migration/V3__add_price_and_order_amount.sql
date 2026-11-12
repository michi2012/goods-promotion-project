-- 상품 가격(원 단위). 기존 상품은 0원으로 채워지며, 0원 상품은 주문 저장 단계에서 구매가 차단된다.
ALTER TABLE goods
    ADD COLUMN price BIGINT NOT NULL DEFAULT 0;

-- 주문 시점 가격 스냅샷. 기존 주문 행은 금액 정보가 없으므로 NULL 허용.
-- AFTER 위치 지정을 하지 않는다: MySQL 8.0.29 미만에서는 AFTER가 INSTANT DDL을 막아
-- 대용량 orders 테이블이 재구성될 수 있다. 끝에 추가하면 버전과 무관하게 INSTANT로 처리된다.
ALTER TABLE orders
    ADD COLUMN unit_price   BIGINT     NULL,
    ADD COLUMN total_amount BIGINT     NULL,
    ADD COLUMN currency     VARCHAR(3) NULL;
