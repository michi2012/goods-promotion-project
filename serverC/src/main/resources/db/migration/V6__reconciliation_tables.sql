-- PG 대사 배치 (docs/specs/pg-reconciliation-batch.md)
-- event_type: APPROVE / CANCEL

-- PG 거래 파일을 적재한 사본. 재시작·키셋 조회·orderId 재확인용.
-- recon_date D에는 D 파일 전체 + D-1 파일의 마지막 몇 분 줄(날짜 경계 확인용)이 함께 들어간다.
-- 같은 줄은 INSERT IGNORE로 한 번만 남는다 (chunk 재시도·재시작).
CREATE TABLE pg_settlement_lines
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    recon_date  DATE         NOT NULL,
    order_id    VARCHAR(255) NOT NULL,
    event_type  VARCHAR(10)  NOT NULL,
    amount      BIGINT       NULL,
    occurred_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_pg_settlement_lines UNIQUE (recon_date, order_id, event_type)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 우리 이벤트 → PG 줄 조회: WHERE recon_date = ? AND order_id = ? AND event_type = ? (uk가 담당)
-- PG 줄 키셋 조회: WHERE recon_date = ? AND occurred_at >= ? AND occurred_at < ? ORDER BY occurred_at, id
CREATE INDEX idx_pg_settlement_lines_occurred ON pg_settlement_lines (recon_date, occurred_at, id);

-- 대사 결과(불일치). 날짜당 한 벌 — 재실행 시 그 날짜를 지우고 다시 쓴다.
-- mismatch_type: OURS_ONLY / PG_ONLY / AMOUNT / STATUS
CREATE TABLE reconciliation_mismatches
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    recon_date    DATE         NOT NULL,
    order_id      VARCHAR(255) NOT NULL,
    event_type    VARCHAR(10)  NOT NULL,
    mismatch_type VARCHAR(20)  NOT NULL,
    our_amount    BIGINT       NULL,
    pg_amount     BIGINT       NULL,
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_reconciliation_mismatches UNIQUE (recon_date, order_id, event_type)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
