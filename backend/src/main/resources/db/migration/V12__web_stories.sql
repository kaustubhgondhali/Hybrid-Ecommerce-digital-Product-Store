-- Web Stories: full-screen, tap-through stories in Google's Web Stories (AMP) format, built in each admin panel and
-- shown at the bottom of the storefront. Pages follow an automatic cover page in sort order; a page can link to one of
-- the storefront's products. Portable SQL (MySQL 8 and H2 in MySQL mode).
CREATE TABLE web_stories (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    storefront    VARCHAR(10)  NOT NULL,
    slug          VARCHAR(140) NOT NULL,
    title         VARCHAR(90)  NOT NULL,
    description   VARCHAR(300),
    cover_key     VARCHAR(255),
    status        VARCHAR(12)  NOT NULL,
    published_at  DATETIME(6),
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    CONSTRAINT uk_web_stories_slug UNIQUE (storefront, slug)
);
CREATE INDEX ix_web_stories_published ON web_stories (storefront, status, published_at);

CREATE TABLE web_story_pages (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    story_id    BIGINT       NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    media_type  VARCHAR(10)  NOT NULL,
    media_key   VARCHAR(255) NOT NULL,
    poster_key  VARCHAR(255),
    width       INT,
    height      INT,
    alt_text    VARCHAR(200),
    headline    VARCHAR(90),
    body        VARCHAR(250),
    product_id  BIGINT,
    cta_label   VARCHAR(40),
    CONSTRAINT fk_web_story_pages_story FOREIGN KEY (story_id) REFERENCES web_stories (id) ON DELETE CASCADE
);
CREATE INDEX ix_web_story_pages_story ON web_story_pages (story_id, sort_order);
