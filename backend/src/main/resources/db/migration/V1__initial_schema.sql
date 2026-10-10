-- Hybrid Product Platform: initial schema (MySQL 8).
-- Money columns are *_minor BIGINT (paise). Times are UTC DATETIME(6).
-- Cross-module references are plain ids (no FK) to keep module boundaries explicit.

-- ============================================================ identity
CREATE TABLE customers (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    email             VARCHAR(254) NOT NULL,
    password_hash     VARCHAR(255) NOT NULL,
    full_name         VARCHAR(120) NOT NULL,
    phone             VARCHAR(30),
    status            VARCHAR(30)  NOT NULL,
    email_verified    BOOLEAN      NOT NULL DEFAULT FALSE,
    failed_logins     INT          NOT NULL DEFAULT 0,
    locked_until      DATETIME(6),
    last_login_at     DATETIME(6),
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    CONSTRAINT uk_customers_email UNIQUE (email)
);

CREATE TABLE admin_users (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    storefront           VARCHAR(10)  NOT NULL,
    email                VARCHAR(254) NOT NULL,
    password_hash        VARCHAR(255) NOT NULL,
    full_name            VARCHAR(120) NOT NULL,
    role                 VARCHAR(40)  NOT NULL,
    status               VARCHAR(20)  NOT NULL,
    must_change_password BOOLEAN      NOT NULL DEFAULT FALSE,
    mfa_secret_enc       VARCHAR(512),
    mfa_enabled          BOOLEAN      NOT NULL DEFAULT FALSE,
    failed_logins        INT          NOT NULL DEFAULT 0,
    locked_until         DATETIME(6),
    last_login_at        DATETIME(6),
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    CONSTRAINT uk_admin_users_sf_email UNIQUE (storefront, email)
);

CREATE TABLE auth_tokens (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    token_hash   CHAR(64)    NOT NULL,
    purpose      VARCHAR(30) NOT NULL,
    customer_id  BIGINT      NOT NULL,
    expires_at   DATETIME(6) NOT NULL,
    used_at      DATETIME(6),
    created_at   DATETIME(6) NOT NULL,
    CONSTRAINT uk_auth_tokens_hash UNIQUE (token_hash)
);

-- ============================================================ catalog
CREATE TABLE categories (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    storefront  VARCHAR(10)  NOT NULL,
    name        VARCHAR(120) NOT NULL,
    slug        VARCHAR(140) NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT uk_categories_sf_slug UNIQUE (storefront, slug)
);

CREATE TABLE products (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    storefront      VARCHAR(10)  NOT NULL,
    product_type    VARCHAR(10)  NOT NULL,
    selling_method  VARCHAR(10)  NOT NULL,
    status          VARCHAR(10)  NOT NULL,
    slug            VARCHAR(160) NOT NULL,
    title           VARCHAR(200) NOT NULL,
    summary         VARCHAR(500),
    description     TEXT,
    brand           VARCHAR(120),
    category_id     BIGINT,
    digital_format  VARCHAR(60),
    delivery_kind   VARCHAR(30),
    type_locked     BOOLEAN      NOT NULL DEFAULT FALSE,
    published_at    DATETIME(6),
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_products_sf_slug UNIQUE (storefront, slug),
    CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES categories (id)
);
CREATE INDEX ix_products_sf_status ON products (storefront, status);

CREATE TABLE product_variants (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id        BIGINT       NOT NULL,
    sku               VARCHAR(80),
    name              VARCHAR(120) NOT NULL,
    price_minor       BIGINT,
    compare_at_minor  BIGINT,
    weight_grams      INT,
    is_default        BOOLEAN      NOT NULL DEFAULT FALSE,
    active            BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order        INT          NOT NULL DEFAULT 0,
    CONSTRAINT uk_variants_sku UNIQUE (sku),
    CONSTRAINT fk_variants_product FOREIGN KEY (product_id) REFERENCES products (id)
);

CREATE TABLE product_images (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id   BIGINT       NOT NULL,
    storage_key  VARCHAR(255) NOT NULL,
    alt_text     VARCHAR(200),
    sort_order   INT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_images_product FOREIGN KEY (product_id) REFERENCES products (id)
);

-- ============================================================ commerce
CREATE TABLE store_settings (
    storefront          VARCHAR(10)  PRIMARY KEY,
    store_name          VARCHAR(120) NOT NULL,
    support_email       VARCHAR(254),
    tax_rate_bps        INT          NOT NULL DEFAULT 0,
    seller_legal_name   VARCHAR(200),
    seller_address      VARCHAR(500),
    seller_tax_id       VARCHAR(40),
    invoice_prefix      VARCHAR(20)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL
);

CREATE TABLE shipping_methods (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    storefront        VARCHAR(10)  NOT NULL,
    name              VARCHAR(120) NOT NULL,
    rate_minor        BIGINT       NOT NULL,
    free_above_minor  BIGINT,
    active            BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order        INT          NOT NULL DEFAULT 0
);

CREATE TABLE carts (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id  BIGINT      NOT NULL,
    storefront   VARCHAR(10) NOT NULL,
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    CONSTRAINT uk_carts_customer_sf UNIQUE (customer_id, storefront)
);

CREATE TABLE cart_items (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    cart_id     BIGINT      NOT NULL,
    product_id  BIGINT      NOT NULL,
    variant_id  BIGINT      NOT NULL,
    quantity    INT         NOT NULL,
    added_at    DATETIME(6) NOT NULL,
    CONSTRAINT uk_cart_items_variant UNIQUE (cart_id, variant_id),
    CONSTRAINT fk_cart_items_cart FOREIGN KEY (cart_id) REFERENCES carts (id) ON DELETE CASCADE
);

CREATE TABLE orders (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_number          VARCHAR(40)  NOT NULL,
    storefront            VARCHAR(10)  NOT NULL,
    customer_id           BIGINT       NOT NULL,
    customer_email        VARCHAR(254) NOT NULL,
    customer_name         VARCHAR(120) NOT NULL,
    status                VARCHAR(30)  NOT NULL,
    currency              CHAR(3)      NOT NULL,
    subtotal_minor        BIGINT       NOT NULL,
    shipping_minor        BIGINT       NOT NULL,
    tax_minor             BIGINT       NOT NULL,
    total_minor           BIGINT       NOT NULL,
    requires_shipping     BOOLEAN      NOT NULL,
    shipping_method_name  VARCHAR(120),
    ship_name             VARCHAR(120),
    ship_phone            VARCHAR(30),
    ship_line1            VARCHAR(200),
    ship_line2            VARCHAR(200),
    ship_city             VARCHAR(100),
    ship_state            VARCHAR(100),
    ship_postal_code      VARCHAR(20),
    ship_country          VARCHAR(2),
    idempotency_key       VARCHAR(80),
    placed_at             DATETIME(6)  NOT NULL,
    expires_at            DATETIME(6),
    paid_at               DATETIME(6),
    cancelled_at          DATETIME(6),
    version               BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_orders_number UNIQUE (order_number),
    CONSTRAINT uk_orders_idem UNIQUE (customer_id, idempotency_key)
);
CREATE INDEX ix_orders_sf_status ON orders (storefront, status);
CREATE INDEX ix_orders_customer ON orders (customer_id);

CREATE TABLE order_lines (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id            BIGINT       NOT NULL,
    product_id          BIGINT       NOT NULL,
    variant_id          BIGINT       NOT NULL,
    product_type        VARCHAR(10)  NOT NULL,
    selling_method      VARCHAR(10)  NOT NULL,
    sku                 VARCHAR(80),
    title               VARCHAR(200) NOT NULL,
    variant_name        VARCHAR(120) NOT NULL,
    unit_price_minor    BIGINT       NOT NULL,
    quantity            INT          NOT NULL,
    line_total_minor    BIGINT       NOT NULL,
    fulfillment_status  VARCHAR(20)  NOT NULL,
    CONSTRAINT fk_order_lines_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT ck_order_lines_direct CHECK (selling_method = 'DIRECT')
);

CREATE TABLE inventory_items (
    variant_id           BIGINT PRIMARY KEY,
    storefront           VARCHAR(10) NOT NULL,
    on_hand              INT         NOT NULL DEFAULT 0,
    reserved             INT         NOT NULL DEFAULT 0,
    low_stock_threshold  INT         NOT NULL DEFAULT 5,
    version              BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_inventory_non_negative CHECK (on_hand >= 0 AND reserved >= 0 AND reserved <= on_hand)
);

CREATE TABLE inventory_reservations (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id    BIGINT      NOT NULL,
    variant_id  BIGINT      NOT NULL,
    quantity    INT         NOT NULL,
    status      VARCHAR(20) NOT NULL,
    created_at  DATETIME(6) NOT NULL
);
CREATE INDEX ix_reservations_order ON inventory_reservations (order_id);

CREATE TABLE inventory_movements (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    variant_id  BIGINT       NOT NULL,
    delta       INT          NOT NULL,
    reason      VARCHAR(20)  NOT NULL,
    order_id    BIGINT,
    admin_id    BIGINT,
    note        VARCHAR(300),
    created_at  DATETIME(6)  NOT NULL
);

CREATE TABLE shipments (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id         BIGINT       NOT NULL,
    carrier          VARCHAR(80)  NOT NULL,
    tracking_number  VARCHAR(120),
    tracking_url     VARCHAR(500),
    status           VARCHAR(20)  NOT NULL,
    shipped_at       DATETIME(6),
    delivered_at     DATETIME(6),
    created_at       DATETIME(6)  NOT NULL
);
CREATE INDEX ix_shipments_order ON shipments (order_id);

CREATE TABLE invoice_sequences (
    storefront  VARCHAR(10) NOT NULL,
    year_value  INT         NOT NULL,
    next_value  BIGINT      NOT NULL,
    PRIMARY KEY (storefront, year_value)
);

CREATE TABLE invoices (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    storefront       VARCHAR(10)  NOT NULL,
    invoice_number   VARCHAR(40)  NOT NULL,
    order_id         BIGINT       NOT NULL,
    seller_snapshot  TEXT         NOT NULL,
    issued_at        DATETIME(6)  NOT NULL,
    CONSTRAINT uk_invoices_number UNIQUE (storefront, invoice_number),
    CONSTRAINT uk_invoices_order UNIQUE (order_id)
);

-- ============================================================ payment
CREATE TABLE payments (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id            BIGINT       NOT NULL,
    storefront          VARCHAR(10)  NOT NULL,
    provider            VARCHAR(20)  NOT NULL,
    provider_order_id   VARCHAR(100) NOT NULL,
    provider_payment_id VARCHAR(100),
    amount_minor        BIGINT       NOT NULL,
    currency            CHAR(3)      NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    failure_reason      VARCHAR(300),
    refunded_minor      BIGINT       NOT NULL DEFAULT 0,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_payments_order UNIQUE (order_id),
    CONSTRAINT uk_payments_provider_order UNIQUE (provider, provider_order_id)
);

CREATE TABLE payment_events (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider           VARCHAR(20)  NOT NULL,
    provider_event_id  VARCHAR(120) NOT NULL,
    event_type         VARCHAR(60)  NOT NULL,
    payment_id         BIGINT,
    received_at        DATETIME(6)  NOT NULL,
    CONSTRAINT uk_payment_events UNIQUE (provider, provider_event_id)
);

-- ============================================================ digital delivery
CREATE TABLE digital_assets (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    storefront     VARCHAR(10)  NOT NULL,
    product_id     BIGINT       NOT NULL,
    file_name      VARCHAR(255) NOT NULL,
    storage_key    VARCHAR(255) NOT NULL,
    content_type   VARCHAR(120) NOT NULL,
    size_bytes     BIGINT       NOT NULL,
    sha256         CHAR(64)     NOT NULL,
    version_label  VARCHAR(40),
    status         VARCHAR(20)  NOT NULL,
    uploaded_by    BIGINT       NOT NULL,
    created_at     DATETIME(6)  NOT NULL
);
CREATE INDEX ix_assets_product ON digital_assets (product_id, status);

CREATE TABLE delivery_policies (
    product_id      BIGINT PRIMARY KEY,
    storefront      VARCHAR(10) NOT NULL,
    download_limit  INT,
    access_days     INT
);

CREATE TABLE license_keys (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    storefront      VARCHAR(10)  NOT NULL,
    product_id      BIGINT       NOT NULL,
    key_enc         VARCHAR(1024) NOT NULL,
    fingerprint     CHAR(64)     NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    entitlement_id  BIGINT,
    assigned_at     DATETIME(6),
    created_at      DATETIME(6)  NOT NULL,
    CONSTRAINT uk_license_fingerprint UNIQUE (product_id, fingerprint)
);
CREATE INDEX ix_license_available ON license_keys (product_id, status);

CREATE TABLE entitlements (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    storefront      VARCHAR(10)  NOT NULL,
    customer_id     BIGINT       NOT NULL,
    product_id      BIGINT       NOT NULL,
    product_title   VARCHAR(200) NOT NULL,
    order_id        BIGINT,
    order_line_id   BIGINT,
    source          VARCHAR(20)  NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    granted_at      DATETIME(6)  NOT NULL,
    expires_at      DATETIME(6),
    revoked_at      DATETIME(6),
    revoke_reason   VARCHAR(300),
    download_limit  INT,
    downloads_used  INT          NOT NULL DEFAULT 0,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_entitlements_line UNIQUE (order_line_id)
);
CREATE INDEX ix_entitlements_customer ON entitlements (customer_id, status);

CREATE TABLE download_tokens (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    token_hash      CHAR(64)    NOT NULL,
    entitlement_id  BIGINT      NOT NULL,
    asset_id        BIGINT      NOT NULL,
    expires_at      DATETIME(6) NOT NULL,
    created_at      DATETIME(6) NOT NULL,
    CONSTRAINT uk_download_tokens_hash UNIQUE (token_hash)
);

-- ============================================================ affiliate
CREATE TABLE affiliate_merchants (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    storefront    VARCHAR(10)  NOT NULL,
    name          VARCHAR(120) NOT NULL,
    domain        VARCHAR(253) NOT NULL,
    sub_id_param  VARCHAR(40),
    allow_http    BOOLEAN      NOT NULL DEFAULT FALSE,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    DATETIME(6)  NOT NULL,
    CONSTRAINT uk_merchants_sf_domain UNIQUE (storefront, domain)
);

CREATE TABLE affiliate_links (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    storefront           VARCHAR(10)   NOT NULL,
    product_id           BIGINT        NOT NULL,
    merchant_id          BIGINT        NOT NULL,
    code                 VARCHAR(20)   NOT NULL,
    destination_url      VARCHAR(2048) NOT NULL,
    display_price_minor  BIGINT,
    price_checked_at     DATETIME(6),
    disclosure           VARCHAR(500),
    active               BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at           DATETIME(6)   NOT NULL,
    updated_at           DATETIME(6)   NOT NULL,
    CONSTRAINT uk_affiliate_links_product UNIQUE (product_id),
    CONSTRAINT uk_affiliate_links_code UNIQUE (code),
    CONSTRAINT fk_affiliate_links_merchant FOREIGN KEY (merchant_id) REFERENCES affiliate_merchants (id)
);

CREATE TABLE affiliate_clicks (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    click_ref       VARCHAR(40)  NOT NULL,
    link_id         BIGINT       NOT NULL,
    storefront      VARCHAR(10)  NOT NULL,
    product_id      BIGINT       NOT NULL,
    customer_id     BIGINT,
    ip_hash         CHAR(64),
    ua_hash         CHAR(64),
    referrer_host   VARCHAR(253),
    suspected_bot   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      DATETIME(6)  NOT NULL,
    CONSTRAINT uk_affiliate_clicks_ref UNIQUE (click_ref)
);
CREATE INDEX ix_clicks_link_time ON affiliate_clicks (link_id, created_at);
CREATE INDEX ix_clicks_sf_time ON affiliate_clicks (storefront, created_at);

CREATE TABLE affiliate_conversions (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    storefront         VARCHAR(10)  NOT NULL,
    merchant_id        BIGINT,
    click_ref          VARCHAR(40),
    external_txn_id    VARCHAR(120) NOT NULL,
    sale_amount_minor  BIGINT,
    commission_minor   BIGINT,
    status             VARCHAR(20)  NOT NULL,
    source             VARCHAR(20)  NOT NULL,
    note               VARCHAR(300),
    reported_at        DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    CONSTRAINT uk_conversions_txn UNIQUE (storefront, external_txn_id)
);

-- ============================================================ notification
CREATE TABLE smtp_settings (
    storefront    VARCHAR(10)  PRIMARY KEY,
    enabled       BOOLEAN      NOT NULL DEFAULT FALSE,
    host          VARCHAR(253),
    port          INT,
    username      VARCHAR(254),
    password_enc  VARCHAR(1024),
    security      VARCHAR(10)  NOT NULL DEFAULT 'STARTTLS',
    from_email    VARCHAR(254),
    from_name     VARCHAR(120),
    updated_at    DATETIME(6)  NOT NULL,
    updated_by    BIGINT
);

CREATE TABLE notification_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    storefront  VARCHAR(10)  NOT NULL,
    recipient   VARCHAR(254) NOT NULL,
    subject     VARCHAR(250) NOT NULL,
    kind        VARCHAR(40)  NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    error       VARCHAR(500),
    created_at  DATETIME(6)  NOT NULL,
    sent_at     DATETIME(6)
);
CREATE INDEX ix_notification_sf_time ON notification_log (storefront, created_at);

-- ============================================================ audit
CREATE TABLE admin_audit_log (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    storefront   VARCHAR(10)  NOT NULL,
    admin_id     BIGINT       NOT NULL,
    admin_email  VARCHAR(254) NOT NULL,
    action       VARCHAR(60)  NOT NULL,
    entity_type  VARCHAR(40),
    entity_id    VARCHAR(60),
    details      VARCHAR(1000),
    created_at   DATETIME(6)  NOT NULL
);
CREATE INDEX ix_audit_sf_time ON admin_audit_log (storefront, created_at);

-- ============================================================ seed configuration (no credentials)
INSERT INTO store_settings (storefront, store_name, tax_rate_bps, invoice_prefix, updated_at)
VALUES ('SHOP', 'Elaashop', 0, 'SHP', CURRENT_TIMESTAMP(6)),
       ('DIGITAL', 'Elaatech', 0, 'DIG', CURRENT_TIMESTAMP(6));

INSERT INTO smtp_settings (storefront, enabled, security, updated_at)
VALUES ('SHOP', FALSE, 'STARTTLS', CURRENT_TIMESTAMP(6)),
       ('DIGITAL', FALSE, 'STARTTLS', CURRENT_TIMESTAMP(6));

INSERT INTO shipping_methods (storefront, name, rate_minor, free_above_minor, active, sort_order)
VALUES ('SHOP', 'Standard delivery', 5000, 99900, TRUE, 0),
       ('DIGITAL', 'Standard delivery', 5000, 99900, TRUE, 0);
