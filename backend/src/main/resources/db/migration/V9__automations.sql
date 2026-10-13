-- Free automations: nightly partner-link checks (dead deals are hidden automatically), weekly top-deals emails,
-- welcome emails, review requests after a purchase, a daily summary for the store team, and Telegram channel posts.

-- Link health: NULL = not checked yet, OK, UNKNOWN (could not verify, e.g. the store blocks robots), BROKEN (page not
-- found on two checks in a row; hidden from shoppers until a later check finds it again).
ALTER TABLE affiliate_links ADD COLUMN health_status VARCHAR(10);
ALTER TABLE affiliate_links ADD COLUMN health_checked_at DATETIME(6);
ALTER TABLE affiliate_links ADD COLUMN health_failures INT NOT NULL DEFAULT 0;
ALTER TABLE affiliate_links ADD COLUMN health_detail VARCHAR(200);

ALTER TABLE crm_automation_settings ADD COLUMN link_check_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE crm_automation_settings ADD COLUMN weekly_digest_enabled BOOLEAN NOT NULL DEFAULT FALSE;
-- 1 = Monday ... 7 = Sunday; sent from 10:00 India time.
ALTER TABLE crm_automation_settings ADD COLUMN weekly_digest_day INT NOT NULL DEFAULT 1;
ALTER TABLE crm_automation_settings ADD COLUMN welcome_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE crm_automation_settings ADD COLUMN review_request_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE crm_automation_settings ADD COLUMN review_request_days INT NOT NULL DEFAULT 7;
ALTER TABLE crm_automation_settings ADD COLUMN daily_summary_enabled BOOLEAN NOT NULL DEFAULT FALSE;
-- Hour of the day (India time) from which the daily summary goes out.
ALTER TABLE crm_automation_settings ADD COLUMN daily_summary_hour INT NOT NULL DEFAULT 9;
ALTER TABLE crm_automation_settings ADD COLUMN telegram_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE crm_automation_settings ADD COLUMN telegram_token_enc VARCHAR(1024);
ALTER TABLE crm_automation_settings ADD COLUMN telegram_chat_id VARCHAR(120);
ALTER TABLE crm_automation_settings ADD COLUMN telegram_post_new BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE crm_automation_settings ADD COLUMN telegram_post_drops BOOLEAN NOT NULL DEFAULT TRUE;
