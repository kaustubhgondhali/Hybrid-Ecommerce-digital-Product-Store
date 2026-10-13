-- Deal content that helps shoppers decide (and ranks in search), original prices for "% off", per-merchant price
-- hiding (Amazon only allows prices that come from its own API), and price history for "lowest in 30 days" and
-- "price drops". Portable SQL (MySQL 8 and H2 in MySQL mode).

ALTER TABLE products ADD COLUMN highlights TEXT;
ALTER TABLE products ADD COLUMN pros TEXT;
ALTER TABLE products ADD COLUMN cons TEXT;
ALTER TABLE products ADD COLUMN verdict VARCHAR(600);
-- Editor's rating in tenths of a star (10 = 1.0, 45 = 4.5, 50 = 5.0).
ALTER TABLE products ADD COLUMN expert_rating INT;

-- Original / maximum retail price at the partner store, to show the discount.
ALTER TABLE affiliate_links ADD COLUMN list_price_minor BIGINT;

-- When true, prices for this merchant are never shown; shoppers see "Check price on <merchant>" instead.
ALTER TABLE affiliate_merchants ADD COLUMN hide_prices BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE affiliate_merchants SET hide_prices = TRUE
 WHERE domain LIKE 'amazon.%' OR domain LIKE 'amzn.%' OR domain = 'a.co';

CREATE TABLE affiliate_price_history (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    storefront   VARCHAR(10) NOT NULL,
    product_id   BIGINT      NOT NULL,
    link_id      BIGINT      NOT NULL,
    price_minor  BIGINT      NOT NULL,
    recorded_at  DATETIME(6) NOT NULL
);
CREATE INDEX ix_price_history_product ON affiliate_price_history (product_id, recorded_at);
CREATE INDEX ix_price_history_sf_time ON affiliate_price_history (storefront, recorded_at);

-- Seed history with today's known prices so "lowest in 30 days" has a starting point.
INSERT INTO affiliate_price_history (storefront, product_id, link_id, price_minor, recorded_at)
SELECT storefront, product_id, id, display_price_minor, COALESCE(price_checked_at, updated_at)
  FROM affiliate_links WHERE display_price_minor IS NOT NULL;
