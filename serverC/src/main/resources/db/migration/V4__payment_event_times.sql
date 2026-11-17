-- PG 대사 기준 시각. 우리가 PG 승인·취소를 기록한 시각(앱 시계)이며, 대사는 이 시각으로 하루 범위를 자른다.
-- 마이그레이션 이전 결제는 NULL로 둔다 (비교할 과거 PG 거래 파일이 없다).
-- 컬럼 추가와 인덱스 추가를 나눈다: 같은 ALTER에 섞으면 INSTANT DDL이 적용되지 않는다.
ALTER TABLE payments
    ADD COLUMN approved_at  DATETIME(6) NULL,
    ADD COLUMN cancelled_at DATETIME(6) NULL;

-- 대사 키셋 페이징: WHERE approved_at >= ? AND approved_at < ? AND (approved_at, id) > (?, ?) ORDER BY approved_at, id
CREATE INDEX idx_payments_approved_at ON payments (approved_at, id);
CREATE INDEX idx_payments_cancelled_at ON payments (cancelled_at, id);
