-- Google Analytics 4 measurement ID and Meta (Facebook/Instagram) Pixel ID per storefront.
-- Both load only after the shopper accepts analytics cookies in the consent banner.
ALTER TABLE store_settings ADD COLUMN ga4_id VARCHAR(20);
ALTER TABLE store_settings ADD COLUMN meta_pixel_id VARCHAR(20);
