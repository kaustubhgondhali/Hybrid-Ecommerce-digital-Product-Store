-- Content pages, SEO, GST invoicing, coupons, reviews, wishlist, COD, PIN codes, image thumbnails,
-- email retry queue, error log. Portable SQL (MySQL 8 and H2 in MySQL mode).

-- ============================================================ content pages (legal / policy / info)
CREATE TABLE content_pages (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    storefront        VARCHAR(10)  NOT NULL,
    slug              VARCHAR(140) NOT NULL,
    title             VARCHAR(200) NOT NULL,
    body              TEXT         NOT NULL,
    meta_title        VARCHAR(200),
    meta_description  VARCHAR(320),
    published         BOOLEAN      NOT NULL DEFAULT TRUE,
    show_in_footer    BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order        INT          NOT NULL DEFAULT 0,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    CONSTRAINT uk_content_pages_sf_slug UNIQUE (storefront, slug)
);

-- ============================================================ SEO
ALTER TABLE products ADD COLUMN meta_title VARCHAR(200);
ALTER TABLE products ADD COLUMN meta_description VARCHAR(320);
ALTER TABLE products ADD COLUMN focus_keyword VARCHAR(100);
ALTER TABLE products ADD COLUMN noindex BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE categories ADD COLUMN description TEXT;
ALTER TABLE categories ADD COLUMN meta_title VARCHAR(200);
ALTER TABLE categories ADD COLUMN meta_description VARCHAR(320);

ALTER TABLE store_settings ADD COLUMN seo_title VARCHAR(200);
ALTER TABLE store_settings ADD COLUMN seo_description VARCHAR(320);
ALTER TABLE store_settings ADD COLUMN google_verification VARCHAR(120);
ALTER TABLE store_settings ADD COLUMN bing_verification VARCHAR(120);
ALTER TABLE store_settings ADD COLUMN robots_extra TEXT;

CREATE TABLE seo_redirects (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    storefront    VARCHAR(10)  NOT NULL,
    from_path     VARCHAR(500) NOT NULL,
    to_path       VARCHAR(500) NOT NULL,
    status_code   INT          NOT NULL DEFAULT 301,
    auto_created  BOOLEAN      NOT NULL DEFAULT FALSE,
    hits          BIGINT       NOT NULL DEFAULT 0,
    last_hit_at   DATETIME(6),
    created_at    DATETIME(6)  NOT NULL,
    CONSTRAINT uk_seo_redirects_from UNIQUE (from_path)
);

CREATE TABLE seo_not_found (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    storefront  VARCHAR(10)  NOT NULL,
    path        VARCHAR(500) NOT NULL,
    hits        BIGINT       NOT NULL DEFAULT 1,
    first_seen  DATETIME(6)  NOT NULL,
    last_seen   DATETIME(6)  NOT NULL,
    CONSTRAINT uk_seo_not_found_path UNIQUE (path)
);

-- ============================================================ GST
ALTER TABLE products ADD COLUMN hsn_sac VARCHAR(8);
ALTER TABLE products ADD COLUMN gst_rate_bps INT;

ALTER TABLE store_settings ADD COLUMN seller_state_code CHAR(2);
ALTER TABLE store_settings ADD COLUMN prices_include_tax BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE store_settings ADD COLUMN default_hsn_sac VARCHAR(8);

ALTER TABLE orders ADD COLUMN buyer_state_code CHAR(2);
ALTER TABLE orders ADD COLUMN buyer_gstin VARCHAR(15);
ALTER TABLE orders ADD COLUMN prices_include_tax BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE orders ADD COLUMN cgst_minor BIGINT NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN sgst_minor BIGINT NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN igst_minor BIGINT NOT NULL DEFAULT 0;

ALTER TABLE order_lines ADD COLUMN hsn_sac VARCHAR(8);
ALTER TABLE order_lines ADD COLUMN gst_rate_bps INT NOT NULL DEFAULT 0;
ALTER TABLE order_lines ADD COLUMN discount_minor BIGINT NOT NULL DEFAULT 0;
ALTER TABLE order_lines ADD COLUMN taxable_minor BIGINT NOT NULL DEFAULT 0;
ALTER TABLE order_lines ADD COLUMN tax_minor BIGINT NOT NULL DEFAULT 0;

-- ============================================================ coupons
CREATE TABLE coupons (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    storefront          VARCHAR(10)  NOT NULL,
    code                VARCHAR(40)  NOT NULL,
    description         VARCHAR(200),
    discount_type       VARCHAR(10)  NOT NULL,
    discount_value      BIGINT       NOT NULL,
    max_discount_minor  BIGINT,
    min_subtotal_minor  BIGINT       NOT NULL DEFAULT 0,
    starts_at           DATETIME(6),
    ends_at             DATETIME(6),
    usage_limit         INT,
    per_customer_limit  INT          NOT NULL DEFAULT 1,
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    CONSTRAINT uk_coupons_sf_code UNIQUE (storefront, code)
);

CREATE TABLE coupon_redemptions (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    coupon_id       BIGINT       NOT NULL,
    customer_id     BIGINT       NOT NULL,
    order_id        BIGINT       NOT NULL,
    discount_minor  BIGINT       NOT NULL,
    status          VARCHAR(10)  NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    CONSTRAINT uk_coupon_redemptions_order UNIQUE (order_id),
    CONSTRAINT fk_coupon_redemptions_coupon FOREIGN KEY (coupon_id) REFERENCES coupons (id)
);
CREATE INDEX ix_coupon_redemptions_coupon ON coupon_redemptions (coupon_id, status);

ALTER TABLE orders ADD COLUMN discount_minor BIGINT NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN coupon_code VARCHAR(40);

-- ============================================================ reviews
CREATE TABLE product_reviews (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    storefront         VARCHAR(10)  NOT NULL,
    product_id         BIGINT       NOT NULL,
    customer_id        BIGINT       NOT NULL,
    author_name        VARCHAR(120) NOT NULL,
    rating             INT          NOT NULL,
    title              VARCHAR(120),
    body               TEXT,
    status             VARCHAR(10)  NOT NULL,
    verified_purchase  BOOLEAN      NOT NULL DEFAULT FALSE,
    admin_reply        TEXT,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    CONSTRAINT uk_reviews_product_customer UNIQUE (product_id, customer_id),
    CONSTRAINT ck_reviews_rating CHECK (rating BETWEEN 1 AND 5)
);
CREATE INDEX ix_reviews_product_status ON product_reviews (product_id, status);
CREATE INDEX ix_reviews_sf_status ON product_reviews (storefront, status);

ALTER TABLE store_settings ADD COLUMN reviews_auto_approve BOOLEAN NOT NULL DEFAULT FALSE;

-- ============================================================ wishlist
CREATE TABLE wishlist_items (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id  BIGINT      NOT NULL,
    storefront   VARCHAR(10) NOT NULL,
    product_id   BIGINT      NOT NULL,
    created_at   DATETIME(6) NOT NULL,
    CONSTRAINT uk_wishlist_customer_product UNIQUE (customer_id, product_id)
);

-- ============================================================ cash on delivery + PIN codes
ALTER TABLE store_settings ADD COLUMN cod_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE store_settings ADD COLUMN cod_max_minor BIGINT;
ALTER TABLE store_settings ADD COLUMN cod_fee_minor BIGINT NOT NULL DEFAULT 0;
ALTER TABLE store_settings ADD COLUMN pincode_mode VARCHAR(10) NOT NULL DEFAULT 'ALL';
ALTER TABLE store_settings ADD COLUMN pincode_list TEXT;
ALTER TABLE store_settings ADD COLUMN cod_pincode_list TEXT;
ALTER TABLE store_settings ADD COLUMN delivery_estimate VARCHAR(80);

ALTER TABLE orders ADD COLUMN payment_method VARCHAR(10) NOT NULL DEFAULT 'ONLINE';
ALTER TABLE orders ADD COLUMN cod_fee_minor BIGINT NOT NULL DEFAULT 0;

-- ============================================================ images
ALTER TABLE product_images ADD COLUMN thumb_key VARCHAR(255);
ALTER TABLE product_images ADD COLUMN width INT;
ALTER TABLE product_images ADD COLUMN height INT;

-- ============================================================ email retry queue
ALTER TABLE notification_log ADD COLUMN body TEXT;
ALTER TABLE notification_log ADD COLUMN attempts INT NOT NULL DEFAULT 0;
ALTER TABLE notification_log ADD COLUMN next_attempt_at DATETIME(6);
CREATE INDEX ix_notification_status_next ON notification_log (status, next_attempt_at);

-- ============================================================ error log (system health)
CREATE TABLE error_log (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    fingerprint  CHAR(64)      NOT NULL,
    method       VARCHAR(10),
    path         VARCHAR(500),
    error_type   VARCHAR(200)  NOT NULL,
    message      VARCHAR(1000),
    stack        TEXT,
    occurrences  BIGINT        NOT NULL DEFAULT 1,
    first_seen   DATETIME(6)   NOT NULL,
    last_seen    DATETIME(6)   NOT NULL,
    resolved     BOOLEAN       NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_error_log_fingerprint UNIQUE (fingerprint)
);

-- ============================================================ sessions (Spring Session JDBC)
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID             CHAR(36)     NOT NULL,
    SESSION_ID             CHAR(36)     NOT NULL,
    CREATION_TIME          BIGINT       NOT NULL,
    LAST_ACCESS_TIME       BIGINT       NOT NULL,
    MAX_INACTIVE_INTERVAL  INT          NOT NULL,
    EXPIRY_TIME            BIGINT       NOT NULL,
    PRINCIPAL_NAME         VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);
CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID  CHAR(36)      NOT NULL,
    ATTRIBUTE_NAME      VARCHAR(200)  NOT NULL,
    ATTRIBUTE_BYTES     BLOB          NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
);
