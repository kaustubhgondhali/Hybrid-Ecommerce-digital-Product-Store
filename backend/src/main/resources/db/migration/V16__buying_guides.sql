-- Buying guides / blog: articles with product cards, published at /{storefront}/guides/{slug}.
-- Portable SQL (MySQL 8 and H2).

CREATE TABLE guides (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    storefront        VARCHAR(10)  NOT NULL,
    slug              VARCHAR(140) NOT NULL,
    title             VARCHAR(200) NOT NULL,
    summary           VARCHAR(400),
    body              MEDIUMTEXT   NOT NULL,
    author_name       VARCHAR(120),
    meta_title        VARCHAR(200),
    meta_description  VARCHAR(320),
    status            VARCHAR(16)  NOT NULL,
    published_at      DATETIME(6),
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    CONSTRAINT uk_guides_sf_slug UNIQUE (storefront, slug)
);
CREATE INDEX ix_guides_sf_status ON guides (storefront, status, published_at);

-- Products shown in a guide, in order (for "Featured in these guides" and the cover image).
CREATE TABLE guide_products (
    guide_id    BIGINT NOT NULL,
    position    INT    NOT NULL,
    product_id  BIGINT NOT NULL,
    PRIMARY KEY (guide_id, position),
    CONSTRAINT fk_guide_products_guide FOREIGN KEY (guide_id) REFERENCES guides (id) ON DELETE CASCADE
);
CREATE INDEX ix_guide_products_product ON guide_products (product_id);
