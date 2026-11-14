-- PG 취소(환불·망취소) 실패 기록. 주문당 한 행이며, 실패가 반복돼도 같은 행을 갱신한다.
-- status: RETRYING(자동 재시도 대기) / EXHAUSTED(자동 재시도 소진, 관리자 처리 대상) / RESOLVED(취소 완료)
CREATE TABLE payment_cancel_failures
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    order_id      VARCHAR(255) NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    attempts      INT          NOT NULL,
    next_retry_at DATETIME(6)  NULL,
    last_error    VARCHAR(500) NULL,
    created_at    DATETIME(6)  NULL,
    updated_at    DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_payment_cancel_failures_order_id UNIQUE (order_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 스케줄러 조회: status = 'RETRYING' AND next_retry_at <= now
CREATE INDEX idx_payment_cancel_failures_status_next ON payment_cancel_failures (status, next_retry_at);
