-- Specifications shown as a table on the product page and lined up on the compare page: one "Name: Value" per line
-- (e.g. "Battery: 30 hours" for gadgets, "Free trial: 14 days" for software). Portable SQL (MySQL 8 and H2).
ALTER TABLE products ADD COLUMN specs TEXT;
