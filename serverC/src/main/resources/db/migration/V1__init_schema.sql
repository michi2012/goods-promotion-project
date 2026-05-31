CREATE TABLE payments
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    order_id         VARCHAR(255) NOT NULL,
    user_id          BIGINT       NOT NULL,
    goods_id         BIGINT       NOT NULL,
    quantity         INT          NOT NULL,
    payment_method   VARCHAR(255) NOT NULL,
    shipping_address VARCHAR(255) NOT NULL,
    zip_code         VARCHAR(255) NOT NULL,
    phone_number     VARCHAR(255) NOT NULL,
    email            VARCHAR(255) NOT NULL,
    delivery_memo    VARCHAR(255),
    client_ip        VARCHAR(255),
    status           VARCHAR(50)  NOT NULL,
    created_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_order_id UNIQUE (order_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE outbox_event
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    aggregate_id VARCHAR(255) NOT NULL,
    topic        VARCHAR(255) NOT NULL,
    payload      TEXT         NOT NULL,
    traceparent  VARCHAR(55),
    created_at   DATETIME(6),
    updated_at   DATETIME(6),
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_outbox_created ON outbox_event (created_at);
