-- Growth features: coupon/offer codes per partner store, price-drop alerts and newsletter sign-ups for visitors
-- (both confirmed by email), a WhatsApp chat number and a "Deal of the Day" per storefront. Portable SQL (MySQL 8 and H2).

CREATE TABLE store_offers (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    storefront   VARCHAR(10)   NOT NULL,
    merchant_id  BIGINT        NOT NULL,
    product_id   BIGINT,
    title        VARCHAR(140)  NOT NULL,
    description  VARCHAR(500),
    code         VARCHAR(40),
    url          VARCHAR(2048) NOT NULL,
    starts_at    DATETIME(6),
    ends_at      DATETIME(6),
    active       BOOLEAN       NOT NULL DEFAULT TRUE,
    verified_at  DATETIME(6),
    clicks       BIGINT        NOT NULL DEFAULT 0,
    created_at   DATETIME(6)   NOT NULL,
    updated_at   DATETIME(6)   NOT NULL,
    CONSTRAINT fk_store_offers_merchant FOREIGN KEY (merchant_id) REFERENCES affiliate_merchants (id)
);
CREATE INDEX ix_store_offers_live ON store_offers (storefront, active, ends_at);

CREATE TABLE price_alerts (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    storefront    VARCHAR(10)  NOT NULL,
    product_id    BIGINT       NOT NULL,
    email         VARCHAR(254) NOT NULL,
    target_minor  BIGINT       NOT NULL,
    token_hash    CHAR(64)     NOT NULL,
    confirmed_at  DATETIME(6),
    notified_at   DATETIME(6),
    created_at    DATETIME(6)  NOT NULL,
    CONSTRAINT uk_price_alerts UNIQUE (product_id, email)
);
CREATE INDEX ix_price_alerts_due ON price_alerts (storefront, confirmed_at, notified_at);
CREATE INDEX ix_price_alerts_token ON price_alerts (token_hash);

CREATE TABLE newsletter_subscribers (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    storefront        VARCHAR(10)  NOT NULL,
    email             VARCHAR(254) NOT NULL,
    token_hash        CHAR(64)     NOT NULL,
    source            VARCHAR(40),
    confirmed_at      DATETIME(6),
    unsubscribed_at   DATETIME(6),
    last_digest_week  VARCHAR(10),
    created_at        DATETIME(6)  NOT NULL,
    CONSTRAINT uk_newsletter_email UNIQUE (storefront, email)
);
CREATE INDEX ix_newsletter_token ON newsletter_subscribers (token_hash);

ALTER TABLE store_settings ADD COLUMN whatsapp_number VARCHAR(20);
ALTER TABLE store_settings ADD COLUMN deal_of_day_product_id BIGINT;
ALTER TABLE store_settings ADD COLUMN deal_of_day_ends_at DATETIME(6);
