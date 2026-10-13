-- Order lines can now be own-stock (DIRECT) or supplier-shipped (DROPSHIP); affiliate items are never ordered here.
ALTER TABLE order_lines DROP CONSTRAINT ck_order_lines_direct;
ALTER TABLE order_lines ADD CONSTRAINT ck_order_lines_onsite CHECK (selling_method IN ('DIRECT', 'DROPSHIP'));

-- Per-storefront switch for cart checkout. NULL keeps the AFFILIATE_ONLY environment default.
ALTER TABLE store_settings ADD COLUMN onsite_checkout BOOLEAN NULL;
