-- Content studio: what to generate automatically when a product is published, and a log of everything generated.
CREATE TABLE content_studio_settings (
    storefront      VARCHAR(10) PRIMARY KEY,
    auto_blog       VARCHAR(10) NOT NULL DEFAULT 'OFF',
    blog_kind       VARCHAR(20) NOT NULL DEFAULT 'REVIEW',
    auto_story      VARCHAR(10) NOT NULL DEFAULT 'OFF',
    auto_newsletter BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at      DATETIME(6) NOT NULL
);

CREATE TABLE content_generation_log (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    storefront VARCHAR(10)  NOT NULL,
    product_id BIGINT,
    kind       VARCHAR(20)  NOT NULL,
    target_id  BIGINT,
    title      VARCHAR(200),
    automatic  BOOLEAN      NOT NULL,
    created_at DATETIME(6)  NOT NULL
);
CREATE INDEX ix_content_log_sf_time ON content_generation_log (storefront, created_at);
CREATE INDEX ix_content_log_product ON content_generation_log (storefront, product_id, kind);
