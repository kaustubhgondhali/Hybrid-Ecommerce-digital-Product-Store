-- Reviews the store team adds for genuine customer feedback received outside the website (email, WhatsApp, phone,
-- in person). They are not tied to a customer account, so customer_id becomes optional, and "source" records where
-- each review came from so the storefront can label it honestly. Portable SQL (MySQL 8 and H2 in MySQL mode).
ALTER TABLE product_reviews MODIFY COLUMN customer_id BIGINT NULL;
ALTER TABLE product_reviews ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'WEBSITE';
ALTER TABLE product_reviews ADD COLUMN added_by VARCHAR(254);
