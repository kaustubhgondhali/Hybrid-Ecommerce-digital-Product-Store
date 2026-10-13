package com.hybrid.platform.crm;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;

/** Per-storefront switches for the automatic reminder emails. Both are off until an admin turns them on. */
@Entity
@Table(name = "crm_automation_settings")
public class CrmAutomationSettings {

    @Id
    @Enumerated(EnumType.STRING)
    private Storefront storefront;

    @Column(name = "cart_reminder_enabled", nullable = false)
    private boolean cartReminderEnabled;

    @Column(name = "cart_reminder_hours", nullable = false)
    private int cartReminderHours = 24;

    @Column(name = "winback_enabled", nullable = false)
    private boolean winbackEnabled;

    @Column(name = "winback_days", nullable = false)
    private int winbackDays = 60;

    @Column(name = "winback_coupon_code")
    private String winbackCouponCode;

    /** Nightly partner-link check; on by default because it only protects shoppers. */
    @Column(name = "link_check_enabled", nullable = false)
    private boolean linkCheckEnabled = true;

    @Column(name = "weekly_digest_enabled", nullable = false)
    private boolean weeklyDigestEnabled;

    /** 1 = Monday ... 7 = Sunday. */
    @Column(name = "weekly_digest_day", nullable = false)
    private int weeklyDigestDay = 1;

    @Column(name = "welcome_enabled", nullable = false)
    private boolean welcomeEnabled;

    @Column(name = "review_request_enabled", nullable = false)
    private boolean reviewRequestEnabled;

    @Column(name = "review_request_days", nullable = false)
    private int reviewRequestDays = 7;

    @Column(name = "daily_summary_enabled", nullable = false)
    private boolean dailySummaryEnabled;

    /** Hour of the day (India time) from which the summary is sent. */
    @Column(name = "daily_summary_hour", nullable = false)
    private int dailySummaryHour = 9;

    @Column(name = "telegram_enabled", nullable = false)
    private boolean telegramEnabled;

    @Column(name = "telegram_token_enc")
    private String telegramTokenEnc;

    @Column(name = "telegram_chat_id")
    private String telegramChatId;

    @Column(name = "telegram_post_new", nullable = false)
    private boolean telegramPostNew = true;

    @Column(name = "telegram_post_drops", nullable = false)
    private boolean telegramPostDrops = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CrmAutomationSettings() {
    }

    public CrmAutomationSettings(Storefront storefront) {
        this.storefront = storefront;
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public Storefront getStorefront() { return storefront; }
    public boolean isCartReminderEnabled() { return cartReminderEnabled; }
    public void setCartReminderEnabled(boolean v) { this.cartReminderEnabled = v; }
    public int getCartReminderHours() { return cartReminderHours; }
    public void setCartReminderHours(int v) { this.cartReminderHours = v; }
    public boolean isWinbackEnabled() { return winbackEnabled; }
    public void setWinbackEnabled(boolean v) { this.winbackEnabled = v; }
    public int getWinbackDays() { return winbackDays; }
    public void setWinbackDays(int v) { this.winbackDays = v; }
    public String getWinbackCouponCode() { return winbackCouponCode; }
    public void setWinbackCouponCode(String v) { this.winbackCouponCode = v; }
    public boolean isLinkCheckEnabled() { return linkCheckEnabled; }
    public void setLinkCheckEnabled(boolean v) { this.linkCheckEnabled = v; }
    public boolean isWeeklyDigestEnabled() { return weeklyDigestEnabled; }
    public void setWeeklyDigestEnabled(boolean v) { this.weeklyDigestEnabled = v; }
    public int getWeeklyDigestDay() { return weeklyDigestDay; }
    public void setWeeklyDigestDay(int v) { this.weeklyDigestDay = v; }
    public boolean isWelcomeEnabled() { return welcomeEnabled; }
    public void setWelcomeEnabled(boolean v) { this.welcomeEnabled = v; }
    public boolean isReviewRequestEnabled() { return reviewRequestEnabled; }
    public void setReviewRequestEnabled(boolean v) { this.reviewRequestEnabled = v; }
    public int getReviewRequestDays() { return reviewRequestDays; }
    public void setReviewRequestDays(int v) { this.reviewRequestDays = v; }
    public boolean isDailySummaryEnabled() { return dailySummaryEnabled; }
    public void setDailySummaryEnabled(boolean v) { this.dailySummaryEnabled = v; }
    public int getDailySummaryHour() { return dailySummaryHour; }
    public void setDailySummaryHour(int v) { this.dailySummaryHour = v; }
    public boolean isTelegramEnabled() { return telegramEnabled; }
    public void setTelegramEnabled(boolean v) { this.telegramEnabled = v; }
    public String getTelegramTokenEnc() { return telegramTokenEnc; }
    public void setTelegramTokenEnc(String v) { this.telegramTokenEnc = v; }
    public String getTelegramChatId() { return telegramChatId; }
    public void setTelegramChatId(String v) { this.telegramChatId = v; }
    public boolean isTelegramPostNew() { return telegramPostNew; }
    public void setTelegramPostNew(boolean v) { this.telegramPostNew = v; }
    public boolean isTelegramPostDrops() { return telegramPostDrops; }
    public void setTelegramPostDrops(boolean v) { this.telegramPostDrops = v; }

    /** Any email automation that needs working SMTP. */
    public boolean anyEmailAutomation() {
        return cartReminderEnabled || winbackEnabled || weeklyDigestEnabled || welcomeEnabled || reviewRequestEnabled
                || dailySummaryEnabled;
    }
    public Instant getUpdatedAt() { return updatedAt; }
}
