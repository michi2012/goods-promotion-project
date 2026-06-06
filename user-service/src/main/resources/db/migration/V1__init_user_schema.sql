CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       VARCHAR(255) NOT NULL,
    username      VARCHAR(20)  NOT NULL,
    role          VARCHAR(255) NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password      VARCHAR(255) NOT NULL,
    phone_number  VARCHAR(20)  NOT NULL,
    created_at    DATETIME(6),
    updated_at    DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_user_id    (user_id),
    UNIQUE KEY uk_users_username   (username),
    UNIQUE KEY uk_users_email      (email),
    UNIQUE KEY uk_users_phone      (phone_number)
);

CREATE TABLE payment_method (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    payment_method_id  VARCHAR(255) NOT NULL,
    user_id            BIGINT       NOT NULL,
    billing_key        VARCHAR(255) NOT NULL,
    card_issuer        VARCHAR(50)  NOT NULL,
    expiry_date        VARCHAR(5)   NOT NULL,
    card_number_masked VARCHAR(50)  NOT NULL,
    is_default         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         DATETIME(6),
    updated_at         DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_method_id (payment_method_id),
    CONSTRAINT fk_payment_method_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE refresh_token (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    username   VARCHAR(255),
    refresh    VARCHAR(500),
    expiration VARCHAR(255),
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (id)
);
