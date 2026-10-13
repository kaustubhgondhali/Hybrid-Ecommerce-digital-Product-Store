-- Sales & demand research: daily product page views and site searches (counts only, no visitor data).
-- Portable SQL (MySQL 8 and H2).

CREATE TABLE product_view_days (
    product_id   BIGINT      NOT NULL,
    view_date    DATE        NOT NULL,
    storefront   VARCHAR(10) NOT NULL,
    views        INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (product_id, view_date),
    CONSTRAINT fk_product_view_days_product FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE CASCADE
);
CREATE INDEX ix_product_view_days_sf_date ON product_view_days (storefront, view_date);

CREATE TABLE search_term_days (
    storefront    VARCHAR(10)  NOT NULL,
    term          VARCHAR(100) NOT NULL,
    search_date   DATE         NOT NULL,
    searches      INT          NOT NULL DEFAULT 0,
    no_results    INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (storefront, term, search_date)
);
CREATE INDEX ix_search_term_days_sf_date ON search_term_days (storefront, search_date);
