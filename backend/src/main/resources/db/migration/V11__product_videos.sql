-- Short product videos (MP4 / WebM) shown in the product gallery next to the images. Files live in the public media
-- area like images; the poster is a still frame the admin's browser captures at upload time. Portable SQL (MySQL 8 and
-- H2 in MySQL mode).
CREATE TABLE product_videos (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id       BIGINT       NOT NULL,
    storage_key      VARCHAR(255) NOT NULL,
    poster_key       VARCHAR(255),
    title            VARCHAR(200),
    duration_seconds INT,
    width            INT,
    height           INT,
    size_bytes       BIGINT       NOT NULL,
    sort_order       INT          NOT NULL DEFAULT 0,
    created_at       DATETIME(6)  NOT NULL,
    CONSTRAINT fk_product_videos_product FOREIGN KEY (product_id) REFERENCES products (id)
);
CREATE INDEX ix_product_videos_product ON product_videos (product_id, sort_order);
