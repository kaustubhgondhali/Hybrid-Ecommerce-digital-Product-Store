-- A product can now link to several partner stores (one link per store), so shoppers can compare prices.
-- DROP CONSTRAINT works for unique keys in MySQL 8.0.19+ and in H2.
ALTER TABLE affiliate_links DROP CONSTRAINT uk_affiliate_links_product;
CREATE UNIQUE INDEX uk_affiliate_links_product_merchant ON affiliate_links (product_id, merchant_id);
