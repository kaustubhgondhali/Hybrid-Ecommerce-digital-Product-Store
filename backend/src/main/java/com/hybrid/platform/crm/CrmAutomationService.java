package com.hybrid.platform.crm;

import com.hybrid.platform.affiliate.AffiliateService;
import com.hybrid.platform.affiliate.LinkHealthService;
import com.hybrid.platform.catalog.CatalogPublicService;
import com.hybrid.platform.common.AppProperties;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.audit.AuditService;
import com.hybrid.platform.common.crypto.CryptoService;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.commerce.coupon.CouponRepositories;
import com.hybrid.platform.commerce.settings.SettingsService;
import com.hybrid.platform.notification.MailService;
import com.hybrid.platform.security.AdminPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.IsoFields;
import java.util.*;

/**
 * Automations that run without anyone in the admin: follow-up emails (saved-cart reminder, win-back, welcome, weekly
 * top deals, review requests), a daily summary for the store team, a nightly partner-link check and Telegram posts.
 * Customer emails only go to customers who opted in, and each one goes out once (enforced by a unique key in
 * crm_automation_log). Everything is free: it runs inside the app and sends through the store's own email account.
 */
@Service
public class CrmAutomationService {

    private static final Logger log = LoggerFactory.getLogger(CrmAutomationService.class);
    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    static final String CART_REMINDER = "CART_REMINDER";
    static final String WINBACK = "WINBACK";
    static final String WELCOME = "WELCOME";
    static final String WEEKLY_DIGEST = "WEEKLY_DIGEST";
    static final String REVIEW_REQUEST = "REVIEW_REQUEST";
    static final String DAILY_SUMMARY = "DAILY_SUMMARY";
    /** Store-level automation rows (summary, Telegram posts) use this customer id. */
    static final long STORE = 0L;
    /** Carts idle for longer than this are not worth a reminder any more. */
    static final Duration CART_MAX_AGE = Duration.ofDays(14);
    /** At most one cart reminder per customer in this period, however often the cart changes. */
    static final Duration CART_COOLDOWN = Duration.ofDays(3);
    static final Duration WINBACK_COOLDOWN = Duration.ofDays(90);
    static final int DIGEST_HOUR = 10;
    static final int MAX_PER_RUN = 200;

    public record AutomationView(boolean cartReminderEnabled, int cartReminderHours, boolean winbackEnabled, int winbackDays,
                                 String winbackCouponCode, boolean welcomeEnabled, boolean weeklyDigestEnabled,
                                 int weeklyDigestDay, boolean reviewRequestEnabled, int reviewRequestDays,
                                 boolean dailySummaryEnabled, int dailySummaryHour, boolean linkCheckEnabled,
                                 long brokenLinks, boolean telegramEnabled, boolean telegramTokenSet, String telegramChatId,
                                 boolean telegramPostNew, boolean telegramPostDrops, Map<String, Long> sentLast30Days,
                                 long subscribers, boolean smtpReady, boolean affiliateOnly, Instant updatedAt) {
    }

    public record AutomationInput(Boolean cartReminderEnabled, Integer cartReminderHours, Boolean winbackEnabled,
                                  Integer winbackDays, String winbackCouponCode, Boolean welcomeEnabled,
                                  Boolean weeklyDigestEnabled, Integer weeklyDigestDay, Boolean reviewRequestEnabled,
                                  Integer reviewRequestDays, Boolean dailySummaryEnabled, Integer dailySummaryHour,
                                  Boolean linkCheckEnabled, Boolean telegramEnabled, String telegramToken,
                                  String telegramChatId, Boolean telegramPostNew, Boolean telegramPostDrops) {
    }

    private final CrmRepositories.AutomationSettingsRepository settings;
    private final CrmQueries queries;
    private final MarketingMailer mailer;
    private final MailService mail;
    private final ConsentService consents;
    private final CouponRepositories.CouponRepository coupons;
    private final CatalogPublicService catalog;
    private final AffiliateService affiliate;
    private final LinkHealthService linkHealth;
    private final TelegramService telegram;
    private final CryptoService crypto;
    private final AuditService audit;
    private final AppProperties props;
    private final SubscriptionService subscriptions;
    private final SettingsService storeSettings;

    public CrmAutomationService(CrmRepositories.AutomationSettingsRepository settings, CrmQueries queries, MarketingMailer mailer,
                                MailService mail, ConsentService consents, CouponRepositories.CouponRepository coupons,
                                CatalogPublicService catalog, AffiliateService affiliate, LinkHealthService linkHealth,
                                TelegramService telegram, CryptoService crypto, AuditService audit, AppProperties props,
                                SubscriptionService subscriptions, SettingsService storeSettings) {
        this.subscriptions = subscriptions;
        this.storeSettings = storeSettings;
        this.settings = settings;
        this.queries = queries;
        this.mailer = mailer;
        this.mail = mail;
        this.consents = consents;
        this.coupons = coupons;
        this.catalog = catalog;
        this.affiliate = affiliate;
        this.linkHealth = linkHealth;
        this.telegram = telegram;
        this.crypto = crypto;
        this.audit = audit;
        this.props = props;
    }

    // ------------------------------------------------------------------ settings

    @Transactional
    public AutomationView get(Storefront sf) {
        return view(entity(sf));
    }

    @Transactional
    public AutomationView update(AdminPrincipal admin, AutomationInput in) {
        CrmAutomationSettings s = entity(admin.storefront());
        boolean emailsBefore = s.anyEmailAutomation();
        if (in.cartReminderHours() != null) s.setCartReminderHours(range(in.cartReminderHours(), 1, 168, "CART_HOURS_INVALID",
                "Send the cart reminder 1 to 168 hours after the last cart change"));
        if (in.winbackDays() != null) s.setWinbackDays(range(in.winbackDays(), 14, 365, "WINBACK_DAYS_INVALID",
                "Win-back emails can go out 14 to 365 days after the last purchase"));
        if (in.weeklyDigestDay() != null) s.setWeeklyDigestDay(range(in.weeklyDigestDay(), 1, 7, "DIGEST_DAY_INVALID",
                "Choose a day from Monday to Sunday"));
        if (in.reviewRequestDays() != null) s.setReviewRequestDays(range(in.reviewRequestDays(), 2, 60, "REVIEW_DAYS_INVALID",
                "Ask for a review 2 to 60 days after the purchase"));
        if (in.dailySummaryHour() != null) s.setDailySummaryHour(range(in.dailySummaryHour(), 0, 23, "SUMMARY_HOUR_INVALID",
                "Choose an hour from 0 to 23"));
        if (in.winbackCouponCode() != null) {
            String code = in.winbackCouponCode().trim().toUpperCase(Locale.ROOT);
            if (!code.isEmpty() && coupons.findByStorefrontAndCode(admin.storefront(), code).isEmpty()) {
                throw ApiException.badRequest("COUPON_NOT_FOUND", "There is no coupon " + code + " in this store");
            }
            s.setWinbackCouponCode(code.isEmpty() ? null : code);
        }
        if (in.cartReminderEnabled() != null) s.setCartReminderEnabled(in.cartReminderEnabled());
        if (in.winbackEnabled() != null) s.setWinbackEnabled(in.winbackEnabled());
        if (in.welcomeEnabled() != null) s.setWelcomeEnabled(in.welcomeEnabled());
        if (in.weeklyDigestEnabled() != null) s.setWeeklyDigestEnabled(in.weeklyDigestEnabled());
        if (in.reviewRequestEnabled() != null) s.setReviewRequestEnabled(in.reviewRequestEnabled());
        if (in.dailySummaryEnabled() != null) s.setDailySummaryEnabled(in.dailySummaryEnabled());
        if (in.linkCheckEnabled() != null) s.setLinkCheckEnabled(in.linkCheckEnabled());
        if (!emailsBefore && s.anyEmailAutomation() && !mailer.smtpReady(admin.storefront())) {
            throw ApiException.rule("SMTP_NOT_READY", "Set up and enable email in Settings → Email (SMTP) first");
        }

        if (in.telegramToken() != null) {
            String token = in.telegramToken().trim();
            if (!token.isEmpty() && !TelegramService.TOKEN.matcher(token).matches()) {
                throw ApiException.badRequest("TELEGRAM_TOKEN_INVALID", "That does not look like a bot token from @BotFather (e.g. 123456789:AA...)");
            }
            s.setTelegramTokenEnc(token.isEmpty() ? null : crypto.encrypt(token));
        }
        if (in.telegramChatId() != null) {
            String chat = in.telegramChatId().trim();
            if (!chat.isEmpty() && !TelegramService.CHAT.matcher(chat).matches()) {
                throw ApiException.badRequest("TELEGRAM_CHAT_INVALID", "Use the channel's @username or its numeric chat id");
            }
            s.setTelegramChatId(chat.isEmpty() ? null : chat);
        }
        if (in.telegramPostNew() != null) s.setTelegramPostNew(in.telegramPostNew());
        if (in.telegramPostDrops() != null) s.setTelegramPostDrops(in.telegramPostDrops());
        if (in.telegramEnabled() != null) {
            if (in.telegramEnabled() && (s.getTelegramTokenEnc() == null || s.getTelegramChatId() == null)) {
                throw ApiException.rule("TELEGRAM_INCOMPLETE", "Add the bot token and the channel first");
            }
            s.setTelegramEnabled(in.telegramEnabled());
        }
        settings.save(s);
        audit.record(admin, "CRM_AUTOMATIONS_UPDATED", "CrmAutomationSettings", admin.storefront().name(),
                "cart=" + s.isCartReminderEnabled() + " winback=" + s.isWinbackEnabled() + " welcome=" + s.isWelcomeEnabled()
                        + " weekly=" + s.isWeeklyDigestEnabled() + " reviews=" + s.isReviewRequestEnabled()
                        + " summary=" + s.isDailySummaryEnabled() + " links=" + s.isLinkCheckEnabled()
                        + " telegram=" + s.isTelegramEnabled() + (in.telegramToken() != null ? " tokenChanged" : ""));
        return view(s);
    }

    /** Sends a test post so the admin can see the bot works; throws with Telegram's explanation when it does not. */
    @Transactional(readOnly = true)
    public void telegramTest(AdminPrincipal admin) {
        CrmAutomationSettings s = entity(admin.storefront());
        if (s.getTelegramTokenEnc() == null || s.getTelegramChatId() == null) {
            throw ApiException.rule("TELEGRAM_INCOMPLETE", "Add the bot token and the channel first");
        }
        String error = telegram.send(crypto.decrypt(s.getTelegramTokenEnc()), s.getTelegramChatId(),
                "Test message from " + mailer.storeName(admin.storefront()) + ". New deals and price drops will appear here.\n"
                        + mailer.storeUrl(admin.storefront()));
        if (error != null) {
            throw ApiException.rule("TELEGRAM_FAILED", "Telegram said: " + error
                    + ". Check that the bot is an admin of the channel and the token is right.");
        }
    }

    /** Runs the partner-link check now (the nightly run does the same). */
    public LinkHealthService.CheckResult checkLinksNow(AdminPrincipal admin) {
        return linkHealth.check(admin.storefront(), 300, Duration.ofMillis(300));
    }

    // ------------------------------------------------------------------ schedules

    @Scheduled(fixedDelayString = "${app.crm.automation-interval-ms:3600000}", initialDelayString = "${app.crm.automation-initial-delay-ms:300000}")
    public void scheduledRun() {
        for (Storefront sf : Storefront.values()) {
            try {
                runDue(sf, Instant.now());
            } catch (RuntimeException e) {
                log.warn("CRM automations failed for {}: {}", sf, e.getMessage());
            }
        }
    }

    @Scheduled(cron = "${app.crm.link-check-cron:0 30 3 * * *}", zone = "Asia/Kolkata")
    public void nightlyLinkCheck() {
        for (Storefront sf : Storefront.values()) {
            boolean enabled = settings.findById(sf).map(CrmAutomationSettings::isLinkCheckEnabled).orElse(true);
            if (!enabled) continue;
            try {
                LinkHealthService.CheckResult r = linkHealth.check(sf, 500, Duration.ofSeconds(1));
                log.info("Nightly link check for {}: {} checked, {} live, {} hidden as dead, {} unverified",
                        sf, r.checked(), r.ok(), r.broken(), r.unknown());
            } catch (RuntimeException e) {
                log.warn("Link check failed for {}: {}", sf, e.getMessage());
            }
        }
    }

    /** Sends every email that is due at {@code now}; returns how many were queued. */
    public int runDue(Storefront sf, Instant now) {
        CrmAutomationSettings s = settings.findById(sf).orElse(null);
        if (s == null || !s.anyEmailAutomation() || !mailer.smtpReady(sf)) return 0;
        int sent = 0;
        ZonedDateTime local = now.atZone(INDIA);
        boolean digestDue = s.isWeeklyDigestEnabled() && local.getDayOfWeek().getValue() == s.getWeeklyDigestDay()
                && local.getHour() >= DIGEST_HOUR;
        String week = local.get(IsoFields.WEEK_BASED_YEAR) + "-W" + local.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        Set<Long> recent = s.isWelcomeEnabled() ? new HashSet<>(queries.newSubscribers(sf, now.minus(Duration.ofDays(7)))) : Set.of();
        List<CatalogPublicService.ProductCard> deals = s.isWeeklyDigestEnabled() || s.isWelcomeEnabled() ? topDeals(sf, 6) : List.of();
        Map<Long, CustomerSnapshot> snapshots = new LinkedHashMap<>();
        queries.snapshots(sf).forEach(c -> snapshots.put(c.id(), c));

        for (CustomerSnapshot c : snapshots.values()) {
            if (sent >= MAX_PER_RUN) break;
            if (!c.active() || !c.subscribed() || c.email() == null) continue;
            if (s.isWelcomeEnabled() && recent.contains(c.id()) && sendWelcome(sf, s, c, deals, now)) {
                sent++;
            } else if (s.isCartReminderEnabled() && cartReminderDue(c, s, now) && sendCartReminder(sf, c, now)) {
                sent++;
            } else if (s.isWinbackEnabled() && winbackDue(c, s, now) && sendWinback(sf, s, c, now)) {
                sent++;
            } else if (digestDue && !deals.isEmpty() && sendDigest(sf, c, deals, week, now)) {
                sent++;
            }
        }
        if (digestDue && !deals.isEmpty() && sent < MAX_PER_RUN) {
            // Newsletter sign-ups without an account get the same deals; customers already reached are skipped.
            Set<String> reached = new HashSet<>();
            snapshots.values().forEach(c -> { if (c.subscribed() && c.email() != null) reached.add(c.email().toLowerCase(Locale.ROOT)); });
            String store = mailer.storeName(sf);
            StringBuilder body = new StringBuilder("Hi,\n\nHere are this week's top deals at ").append(store).append(":\n");
            appendDeals(body, sf, deals);
            body.append("\nSee all deals: ").append(mailer.storeUrl(sf)).append("\n");
            if (storeSettings.affiliateOnly(sf)) body.append("\nPartner prices change often, so check the current price before you buy.\n");
            sent += subscriptions.sendDigest(sf, "This week's top deals at " + store, body.toString(), week, reached, MAX_PER_RUN - sent);
        }
        if (s.isReviewRequestEnabled()) {
            Instant to = now.minus(Duration.ofDays(s.getReviewRequestDays()));
            for (CrmQueries.ReviewCandidate r : queries.reviewCandidates(sf, to.minus(Duration.ofDays(60)), to)) {
                if (sent >= MAX_PER_RUN) break;
                CustomerSnapshot c = snapshots.get(r.customerId());
                if (c == null || !c.active() || !c.subscribed() || c.email() == null) continue;
                if (sendReviewRequest(sf, c, r, now)) sent++;
            }
        }
        if (s.isDailySummaryEnabled() && local.getHour() >= s.getDailySummaryHour()) {
            sendDailySummary(sf, local.toLocalDate(), now);
        }
        if (sent > 0) log.info("CRM automations queued {} email(s) for {}", sent, sf);
        return sent;
    }

    static boolean cartReminderDue(CustomerSnapshot c, CrmAutomationSettings s, Instant now) {
        if (!c.cartUntouchedSinceSaving()) return false;
        Instant changed = c.cartUpdatedAt();
        return !changed.isAfter(now.minus(Duration.ofHours(s.getCartReminderHours()))) && changed.isAfter(now.minus(CART_MAX_AGE));
    }

    static boolean winbackDue(CustomerSnapshot c, CrmAutomationSettings s, Instant now) {
        Instant last = c.lastPurchaseAt();
        return c.purchases() > 0 && last != null && !last.isAfter(now.minus(Duration.ofDays(s.getWinbackDays())))
                && last.isAfter(now.minus(Duration.ofDays(365)));
    }

    // ------------------------------------------------------------------ emails

    private boolean sendCartReminder(Storefront sf, CustomerSnapshot c, Instant now) {
        if (queries.automationSentSince(sf, c.id(), CART_REMINDER, now.minus(CART_COOLDOWN))) return false;
        if (!queries.claimAutomation(sf, c.id(), CART_REMINDER, "cart:" + c.cartUpdatedAt().toEpochMilli(), now)) return false;
        List<CrmQueries.Line> lines = queries.cartLines(sf, c.id(), 5);
        String store = mailer.storeName(sf);
        StringBuilder body = new StringBuilder("Hi ").append(c.firstName()).append(",\n\n")
                .append(storeSettings.affiliateOnly(sf) ? "You saved these deals at " : "You left these in your cart at ").append(store).append(":\n");
        for (CrmQueries.Line l : lines) {
            body.append("- ").append(l.title()).append(l.quantity() > 1 ? " (x" + l.quantity() + ")" : "")
                    .append("\n  ").append(mailer.productUrl(sf, l.slug())).append("\n");
        }
        if (c.cartItems() > lines.size()) body.append("...and ").append(c.cartItems() - lines.size()).append(" more.\n");
        body.append("\nPick up where you left off: ").append(mailer.storeUrl(sf)).append("/cart\n");
        if (storeSettings.affiliateOnly(sf)) body.append("\nPartner prices change often, so check the current price before you buy.\n");
        String subject = storeSettings.affiliateOnly(sf) ? "Your saved deals at " + store + " are waiting"
                : "You left " + (c.cartItems() == 1 ? "an item" : c.cartItems() + " items") + " in your " + store + " cart";
        mailer.send(sf, c.id(), c.email(), subject, body.toString(), "CRM_CART_REMINDER");
        return true;
    }

    private boolean sendWinback(Storefront sf, CrmAutomationSettings s, CustomerSnapshot c, Instant now) {
        if (queries.automationSentSince(sf, c.id(), WINBACK, now.minus(WINBACK_COOLDOWN))) return false;
        if (!queries.claimAutomation(sf, c.id(), WINBACK, "winback:" + c.lastPurchaseAt().toEpochMilli(), now)) return false;
        String store = mailer.storeName(sf);
        StringBuilder body = new StringBuilder("Hi ").append(c.firstName()).append(",\n\nIt has been a while since your last visit to ")
                .append(store).append(". Here is what is new:\n");
        for (CrmQueries.Line l : queries.latestProducts(sf, 3)) {
            body.append("- ").append(l.title()).append("\n  ").append(mailer.productUrl(sf, l.slug())).append("\n");
        }
        if (s.getWinbackCouponCode() != null && !storeSettings.affiliateOnly(sf)) {
            body.append("\nAs a thank-you for coming back, use code ").append(s.getWinbackCouponCode()).append(" at checkout.\n");
        }
        body.append("\nSee everything: ").append(mailer.storeUrl(sf)).append("\n");
        mailer.send(sf, c.id(), c.email(), "We miss you at " + store, body.toString(), "CRM_WINBACK");
        return true;
    }

    private boolean sendWelcome(Storefront sf, CrmAutomationSettings s, CustomerSnapshot c,
                                List<CatalogPublicService.ProductCard> deals, Instant now) {
        if (!queries.claimAutomation(sf, c.id(), WELCOME, "welcome", now)) return false;
        String store = mailer.storeName(sf);
        StringBuilder body = new StringBuilder("Hi ").append(c.firstName()).append(",\n\nWelcome to ").append(store)
                .append("! Thanks for signing up for our deals.\n");
        if (!deals.isEmpty()) {
            body.append("\nHere are a few of today's best picks:\n");
            appendDeals(body, sf, deals.subList(0, Math.min(3, deals.size())));
        }
        body.append("\nSave anything you like to your cart or wishlist and we will tell you when the price drops.");
        if (s.isWeeklyDigestEnabled()) body.append(" You will also get our top deals once a week.");
        body.append("\n\nStart browsing: ").append(mailer.storeUrl(sf)).append("\n");
        mailer.send(sf, c.id(), c.email(), "Welcome to " + store + ": today's best deals inside", body.toString(), "CRM_WELCOME");
        return true;
    }

    private boolean sendDigest(Storefront sf, CustomerSnapshot c, List<CatalogPublicService.ProductCard> deals, String week, Instant now) {
        if (!queries.claimAutomation(sf, c.id(), WEEKLY_DIGEST, week, now)) return false;
        String store = mailer.storeName(sf);
        StringBuilder body = new StringBuilder("Hi ").append(c.firstName()).append(",\n\nHere are this week's top deals at ")
                .append(store).append(":\n");
        appendDeals(body, sf, deals);
        body.append("\nSee all deals: ").append(mailer.storeUrl(sf)).append("\n");
        if (storeSettings.affiliateOnly(sf)) body.append("\nPartner prices change often, so check the current price before you buy.\n");
        mailer.send(sf, c.id(), c.email(), "This week's top deals at " + store, body.toString(), "CRM_WEEKLY_DIGEST");
        return true;
    }

    private boolean sendReviewRequest(Storefront sf, CustomerSnapshot c, CrmQueries.ReviewCandidate r, Instant now) {
        if (!queries.claimAutomation(sf, c.id(), REVIEW_REQUEST, r.ref(), now)) return false;
        String body = "Hi " + c.firstName() + ",\n\nHow are you getting on with " + r.title() + "?\n\n"
                + "A short review helps other shoppers choose well, and takes less than a minute:\n"
                + mailer.productUrl(sf, r.slug()) + "#reviews\n\nThank you!";
        mailer.send(sf, c.id(), c.email(), "How do you like " + r.title() + "?", body, "CRM_REVIEW_REQUEST");
        return true;
    }

    /** Morning numbers for the store team: what happened in the last 24 hours and what needs attention. */
    void sendDailySummary(Storefront sf, LocalDate today, Instant now) {
        if (!queries.claimAutomation(sf, STORE, DAILY_SUMMARY, today.toString(), now)) return;
        CrmQueries.DailyStats d = queries.dailyStats(sf, now.minus(Duration.ofHours(24)), now.minus(affiliate.priceMaxAge()), today);
        String store = mailer.storeName(sf);
        String admin = mailer.storeUrl(sf) + "/admin";
        StringBuilder b = new StringBuilder(store + " – the last 24 hours\n\n")
                .append("Partner clicks: ").append(d.clicks()).append("\n")
                .append("Partner sales reported: ").append(d.partnerSales())
                .append(d.commissionMinor() > 0 ? " (commission " + MarketingMailer.rupees(d.commissionMinor()) + ")" : "").append("\n");
        if (!storeSettings.affiliateOnly(sf) || d.orders() > 0) {
            b.append("Orders: ").append(d.orders()).append(d.revenueMinor() > 0 ? " (" + MarketingMailer.rupees(d.revenueMinor()) + ")" : "").append("\n");
        }
        b.append("New customers: ").append(d.newCustomers()).append("\n")
                .append("New email subscribers: ").append(d.newSubscribers()).append("\n")
                .append("Automatic emails sent: ").append(d.automatedEmails()).append("\n\nNeeds attention\n")
                .append("- Dead partner links hidden: ").append(d.brokenLinks()).append("\n");
        d.newlyBroken().forEach(x -> b.append("    ").append(x).append("\n"));
        b.append("- Reviews waiting for your approval: ").append(d.pendingReviews()).append("\n")
                .append("- Prices to re-check: ").append(d.stalePrices()).append("\n")
                .append("- Follow-ups due today or overdue: ").append(d.tasksDue()).append("\n\n")
                .append("Open the admin: ").append(admin).append("\n")
                .append("CRM: ").append(admin).append("#/crm\n\n")
                .append("You get this because the daily summary is on in CRM → Automations.");
        for (String email : queries.masterAdminEmails(sf)) {
            mail.send(sf, email, store + " daily summary – " + today, b.toString(), "DAILY_SUMMARY");
        }
    }

    private void appendDeals(StringBuilder body, Storefront sf, List<CatalogPublicService.ProductCard> deals) {
        for (CatalogPublicService.ProductCard p : deals) {
            body.append("- ").append(p.title()).append("\n  ").append(priceLine(p)).append("\n  ")
                    .append(mailer.productUrl(sf, p.slug())).append("\n");
        }
    }

    /** "₹1,299 (was ₹1,999, 35% off) at Flipkart" or "Check the latest price at Amazon". */
    static String priceLine(CatalogPublicService.ProductCard p) {
        String at = p.merchantName() == null ? "" : " at " + p.merchantName();
        if (p.priceMinor() == null) return "Check the latest price" + at;
        String line = MarketingMailer.rupees(p.priceMinor());
        if (p.discountPercent() != null && p.compareAtMinor() != null) {
            line += " (was " + MarketingMailer.rupees(p.compareAtMinor()) + ", " + p.discountPercent() + "% off)";
        }
        return line + at;
    }

    /** Price drops first, then the biggest discounts, then what shoppers click, then the newest. */
    List<CatalogPublicService.ProductCard> topDeals(Storefront sf, int limit) {
        CatalogPublicService.Home home = catalog.home(sf);
        LinkedHashMap<Long, CatalogPublicService.ProductCard> picks = new LinkedHashMap<>();
        for (List<CatalogPublicService.ProductCard> section : List.of(home.priceDrops(), home.topDeals(), home.trending(), home.featured())) {
            for (CatalogPublicService.ProductCard c : section) {
                if (picks.size() < limit) picks.putIfAbsent(c.id(), c);
            }
        }
        return new ArrayList<>(picks.values());
    }

    // ------------------------------------------------------------------ helpers

    private static int range(int value, int min, int max, String code, String message) {
        if (value < min || value > max) throw ApiException.badRequest(code, message);
        return value;
    }

    private CrmAutomationSettings entity(Storefront sf) {
        return settings.findById(sf).orElseGet(() -> settings.save(new CrmAutomationSettings(sf)));
    }

    private AutomationView view(CrmAutomationSettings s) {
        Storefront sf = s.getStorefront();
        Map<String, Long> sent = queries.automationCounts(sf, Instant.now().minus(Duration.ofDays(30)));
        for (String k : List.of(WELCOME, WEEKLY_DIGEST, REVIEW_REQUEST)) sent.putIfAbsent(k, 0L);
        return new AutomationView(s.isCartReminderEnabled(), s.getCartReminderHours(), s.isWinbackEnabled(), s.getWinbackDays(),
                s.getWinbackCouponCode(), s.isWelcomeEnabled(), s.isWeeklyDigestEnabled(), s.getWeeklyDigestDay(),
                s.isReviewRequestEnabled(), s.getReviewRequestDays(), s.isDailySummaryEnabled(), s.getDailySummaryHour(),
                s.isLinkCheckEnabled(), queries.brokenLinkCount(sf), s.isTelegramEnabled(), s.getTelegramTokenEnc() != null,
                s.getTelegramChatId(), s.isTelegramPostNew(), s.isTelegramPostDrops(), sent, consents.subscriberCount(sf),
                mailer.smtpReady(sf), storeSettings.affiliateOnly(sf), s.getUpdatedAt());
    }
}
