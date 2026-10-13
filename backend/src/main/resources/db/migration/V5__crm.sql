-- CRM: marketing consent, staff notes, tags, follow-up tasks, email campaigns and automated reminders.
-- Portable SQL (MySQL 8 and H2 in MySQL mode). Cross-module references are plain ids (no FK).

-- The storefront a customer signed up on, so new sign-ups show up in that store's CRM before they do anything.
ALTER TABLE customers ADD COLUMN signup_storefront VARCHAR(10);

-- Marketing email consent, per customer and storefront. Only the customer can opt in; unsubscribing is one click.
CREATE TABLE marketing_consents (
    customer_id  BIGINT      NOT NULL,
    storefront   VARCHAR(10) NOT NULL,
    subscribed   BOOLEAN     NOT NULL,
    source       VARCHAR(20) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (customer_id, storefront)
);
CREATE INDEX ix_marketing_consents_sf ON marketing_consents (storefront, subscribed);

CREATE TABLE crm_notes (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    storefront   VARCHAR(10)   NOT NULL,
    customer_id  BIGINT        NOT NULL,
    admin_id     BIGINT        NOT NULL,
    admin_email  VARCHAR(254)  NOT NULL,
    body         VARCHAR(2000) NOT NULL,
    created_at   DATETIME(6)   NOT NULL
);
CREATE INDEX ix_crm_notes_customer ON crm_notes (storefront, customer_id, created_at);

CREATE TABLE crm_tags (
    storefront   VARCHAR(10) NOT NULL,
    customer_id  BIGINT      NOT NULL,
    tag          VARCHAR(30) NOT NULL,
    created_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (storefront, customer_id, tag)
);

CREATE TABLE crm_tasks (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    storefront        VARCHAR(10)  NOT NULL,
    customer_id       BIGINT       NOT NULL,
    title             VARCHAR(200) NOT NULL,
    due_on            DATE,
    done_at           DATETIME(6),
    created_by        BIGINT       NOT NULL,
    created_by_email  VARCHAR(254) NOT NULL,
    created_at        DATETIME(6)  NOT NULL
);
CREATE INDEX ix_crm_tasks_open ON crm_tasks (storefront, done_at, due_on);
CREATE INDEX ix_crm_tasks_customer ON crm_tasks (storefront, customer_id);

CREATE TABLE crm_campaigns (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    storefront        VARCHAR(10)  NOT NULL,
    name              VARCHAR(120) NOT NULL,
    segment           VARCHAR(30)  NOT NULL,
    subject           VARCHAR(200) NOT NULL,
    body              TEXT         NOT NULL,
    coupon_code       VARCHAR(40),
    status            VARCHAR(10)  NOT NULL,
    recipients        INT          NOT NULL DEFAULT 0,
    created_by_email  VARCHAR(254) NOT NULL,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    sent_at           DATETIME(6)
);
CREATE INDEX ix_crm_campaigns_sf ON crm_campaigns (storefront, created_at);

CREATE TABLE crm_campaign_recipients (
    campaign_id  BIGINT      NOT NULL,
    customer_id  BIGINT      NOT NULL,
    sent_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (campaign_id, customer_id)
);
CREATE INDEX ix_crm_recipients_customer ON crm_campaign_recipients (customer_id);

CREATE TABLE crm_automation_settings (
    storefront             VARCHAR(10) PRIMARY KEY,
    cart_reminder_enabled  BOOLEAN     NOT NULL DEFAULT FALSE,
    cart_reminder_hours    INT         NOT NULL DEFAULT 24,
    winback_enabled        BOOLEAN     NOT NULL DEFAULT FALSE,
    winback_days           INT         NOT NULL DEFAULT 60,
    winback_coupon_code    VARCHAR(40),
    updated_at             DATETIME(6) NOT NULL
);

-- One row per automated email; the unique key makes each reminder go out once even with several app instances.
CREATE TABLE crm_automation_log (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    storefront   VARCHAR(10) NOT NULL,
    customer_id  BIGINT      NOT NULL,
    kind         VARCHAR(20) NOT NULL,
    ref          VARCHAR(80) NOT NULL,
    sent_at      DATETIME(6) NOT NULL,
    CONSTRAINT uk_crm_automation_once UNIQUE (storefront, customer_id, kind, ref)
);
CREATE INDEX ix_crm_automation_recent ON crm_automation_log (storefront, kind, sent_at);
