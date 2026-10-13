-- Product-page image rendition (max 1200 px wide). NULL means the original is used as is.
ALTER TABLE product_images ADD COLUMN large_key VARCHAR(255);
