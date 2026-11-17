-- 모의 PG의 거래 원장 (실제 PG사 내부 데이터를 흉내). 일자별 PG 거래 파일(CSV)을 만드는 데만 쓰고,
-- 대사 배치는 이 테이블이 아니라 파일을 읽는다. 결제·취소 한 번씩만 기록한다 (재시도해도 같은 행).
-- event_type: APPROVE / CANCEL
CREATE TABLE mock_pg_transactions
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    order_id    VARCHAR(255) NOT NULL,
    event_type  VARCHAR(10)  NOT NULL,
    amount      BIGINT       NULL,
    occurred_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_mock_pg_transactions_order_event UNIQUE (order_id, event_type)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 파일 생성: WHERE occurred_at >= ? AND occurred_at < ?
CREATE INDEX idx_mock_pg_transactions_occurred ON mock_pg_transactions (occurred_at);
