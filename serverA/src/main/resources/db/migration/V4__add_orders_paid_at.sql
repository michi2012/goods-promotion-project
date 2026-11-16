-- 결제 완료 시각 (환불 가능 기간 "결제 후 7일" 판단용 — docs/specs/user-refund.md "DB 변경").
-- NULL 허용 + 끝에 추가: 기존 행이 있어도 INSTANT DDL로 처리된다 (V3와 같은 이유로 AFTER 미사용).
ALTER TABLE orders
    ADD COLUMN paid_at DATETIME(6) NULL;

-- 기존 PAID 주문은 PAID 이후 상태 변경이 없어 updated_at이 결제 확정 시각이다.
-- 새 코드는 PAID 주문의 paid_at이 NULL인 경우를 다루지 않는다.
UPDATE orders
SET paid_at = updated_at
WHERE status = 'PAID'
  AND paid_at IS NULL;
