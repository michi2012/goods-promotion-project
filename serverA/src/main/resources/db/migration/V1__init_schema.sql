CREATE TABLE goods
(
    id    BIGINT      NOT NULL AUTO_INCREMENT,
    name  VARCHAR(255),
    stock INT         NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE orders
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    order_id         VARCHAR(255) NOT NULL,
    user_id          BIGINT,
    goods_id         BIGINT,
    quantity         INT          NOT NULL,
    payment_method   VARCHAR(255),
    shipping_address VARCHAR(255),
    zip_code         VARCHAR(255),
    phone_number     VARCHAR(255),
    email            VARCHAR(255),
    delivery_memo    VARCHAR(255),
    client_ip        VARCHAR(255),
    status           VARCHAR(255) NOT NULL,
    created_at       DATETIME(6),
    updated_at       DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_orders_order_id UNIQUE (order_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_orders_order_id ON orders (order_id);
CREATE INDEX idx_orders_status_created ON orders (status, created_at);

CREATE TABLE dead_letter
(
    id       BIGINT       NOT NULL AUTO_INCREMENT,
    order_id VARCHAR(255),
    goods_id BIGINT,
    quantity INT          NOT NULL,
    reason   VARCHAR(1000),
    status   VARCHAR(255) NOT NULL,
    PRIMARY KEY (id)
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
