package com.hybrid.platform.crm;

import com.hybrid.platform.affiliate.AffiliateEvents;
import com.hybrid.platform.catalog.CatalogPublicService;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.crypto.Hashing;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.crm.SubscriptionRepositories.NewsletterRepository;
import com.hybrid.platform.crm.SubscriptionRepositories.PriceAlertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Visitor sign-ups that need no account: price-drop alerts ("email me below ₹X") and the weekly deals newsletter.
 * Both are confirmed from the inbox first (so nobody can sign up someone else), and every email has a one-click link
 * to stop it. Alerts never fire for stores whose prices we may not show (e.g. Amazon).
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public record AlertInput(String slug, String email, Long targetPrice) {
    }

    public record SignupInput(String email, String source) {
    }

    /** CHECK_EMAIL: a confirmation was sent; ACTIVE: already confirmed (target updated). */
    public record SignupResult(String status, Long targetMinor) {
    }

    /** Where to send the visitor after they click a link in the email. */
    public record LinkResult(Storefront storefront, String slug, boolean ok) {
    }

    private final PriceAlertRepository alerts;
    private final NewsletterRepository subscribers;
    private final CatalogPublicService catalog;
    private final MarketingMailer mailer;

    public SubscriptionService(PriceAlertRepository alerts, NewsletterRepository subscribers, CatalogPublicService catalog,
                               MarketingMailer mailer) {
        this.alerts = alerts;
        this.subscribers = subscribers;
        this.catalog = catalog;
        this.mailer = mailer;
    }

    // ------------------------------------------------------------------ price alerts

    @Transactional
    public SignupResult requestAlert(Storefront sf, AlertInput in) {
        String email = email(in == null ? null : in.email());
        CatalogPublicService.ProductDetail p = catalog.detail(sf, in.slug() == null ? "" : in.slug());
        Long price = p.affiliate() != null ? p.affiliate().displayPriceMinor() : null;
        if (price == null) {
            throw ApiException.rule("PRICE_ALERT_UNAVAILABLE", "Price alerts aren't available for this deal");
        }
        long target = in.targetPrice() == null ? price - 100 : in.targetPrice() * 100;
        if (target <= 0 || target >= price) {
            throw ApiException.badRequest("INVALID_TARGET", "Choose a price below today's " + MarketingMailer.rupees(price));
        }
        PriceAlert a = alerts.findByProductIdAndEmail(p.id(), email).orElseGet(() -> new PriceAlert(sf, p.id(), email));
        a.setTargetMinor(target);
        if (a.isWaiting()) {
            alerts.save(a);
            return new SignupResult("ACTIVE", target);
        }
        String token = Hashing.randomToken();
        a.setTokenHash(Hashing.sha256Hex(token));
        a.setConfirmedAt(null);
        a.setNotifiedAt(null);
        alerts.save(a);
        String store = mailer.storeName(sf);
        mailer.sendTransactional(sf, email, "Confirm your price alert for " + p.title(),
                "Hi,\n\nYou asked " + store + " to email you when \"" + p.title() + "\" drops below " + MarketingMailer.rupees(target)
                        + " (today: " + MarketingMailer.rupees(price) + ").\n\nConfirm your alert:\n"
                        + mailer.siteUrl("/api/price-alerts/confirm?t=" + token)
                        + "\n\nChanged your mind later? Cancel the alert: " + mailer.siteUrl("/api/price-alerts/cancel?t=" + token)
                        + "\n\nDidn't ask for this? Just ignore this email and nothing will be sent.", "PRICE_ALERT_CONFIRM");
        return new SignupResult("CHECK_EMAIL", target);
    }

    @Transactional
    public LinkResult confirmAlert(String token) {
        Optional<PriceAlert> a = token == null ? Optional.empty() : alerts.findByTokenHash(Hashing.sha256Hex(token));
        if (a.isEmpty()) return new LinkResult(null, null, false);
        if (a.get().getConfirmedAt() == null) a.get().setConfirmedAt(Instant.now());
        return new LinkResult(a.get().getStorefront(), slugOf(a.get()), true);
    }

    @Transactional
    public LinkResult cancelAlert(String token) {
        Optional<PriceAlert> a = token == null ? Optional.empty() : alerts.findByTokenHash(Hashing.sha256Hex(token));
        if (a.isEmpty()) return new LinkResult(null, null, false);
        LinkResult r = new LinkResult(a.get().getStorefront(), slugOf(a.get()), true);
        alerts.delete(a.get());
        return r;
    }

    /** Hourly: email everyone whose target price has been reached. */
    @Scheduled(cron = "${app.alerts.cron:0 10 * * * *}", zone = "Asia/Kolkata")
    @Transactional
    public void checkAlerts() {
        for (Storefront sf : Storefront.values()) {
            try {
                check(sf, alerts.findByStorefrontAndConfirmedAtIsNotNullAndNotifiedAtIsNull(sf));
            } catch (RuntimeException e) {
                log.warn("Price alert check failed for {}: {}", sf, e.getMessage());
            }
        }
    }

    /** A partner price just dropped: check that product's alerts straight away. */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPriceDropped(AffiliateEvents.PriceDropped e) {
        check(e.storefront(), alerts.findByProductIdAndConfirmedAtIsNotNullAndNotifiedAtIsNull(e.productId()));
    }

    /** Sends the due alerts; returns how many were sent. */
    int check(Storefront sf, List<PriceAlert> due) {
        if (due.isEmpty()) return 0;
        Map<Long, CatalogPublicService.ProductCard> cards = new HashMap<>();
        catalog.cards(sf, due.stream().map(PriceAlert::getProductId).distinct().toList()).forEach(c -> cards.put(c.id(), c));
        int sent = 0;
        String store = mailer.storeName(sf);
        for (PriceAlert a : due) {
            CatalogPublicService.ProductCard c = cards.get(a.getProductId());
            if (c == null || c.checkPrice() || c.priceMinor() == null || c.priceMinor() > a.getTargetMinor()) continue;
            a.setNotifiedAt(Instant.now());
            mailer.sendTransactional(sf, a.getEmail(), "Price drop: " + c.title() + " is now " + MarketingMailer.rupees(c.priceMinor()),
                    "Hi,\n\nGood news: \"" + c.title() + "\" is now " + CrmAutomationService.priceLine(c)
                            + ", below the " + MarketingMailer.rupees(a.getTargetMinor()) + " you asked for.\n\n"
                            + "See the deal: " + mailer.productUrl(sf, c.slug())
                            + "\n\nPartner prices change often, so check the current price before you buy."
                            + "\n\nThis was a one-time alert from " + store + ". You can set a new one on the product page.", "PRICE_ALERT");
            sent++;
        }
        if (sent > 0) log.info("Sent {} price alert(s) for {}", sent, sf);
        return sent;
    }

    @Transactional(readOnly = true)
    public long waitingAlerts(Storefront sf) {
        return alerts.countByStorefrontAndConfirmedAtIsNotNullAndNotifiedAtIsNull(sf);
    }

    private String slugOf(PriceAlert a) {
        List<CatalogPublicService.ProductCard> c = catalog.cards(a.getStorefront(), List.of(a.getProductId()));
        return c.isEmpty() ? null : c.get(0).slug();
    }

    // ------------------------------------------------------------------ newsletter

    @Transactional
    public SignupResult subscribe(Storefront sf, SignupInput in) {
        String email = email(in == null ? null : in.email());
        String source = in.source() == null ? null : in.source().strip();
        if (source != null && (source.isEmpty() || source.length() > 40)) source = null;
        NewsletterSubscriber s = subscribers.findByStorefrontAndEmail(sf, email).orElse(null);
        if (s != null && s.isActive()) return new SignupResult("ACTIVE", null);
        if (s == null) s = new NewsletterSubscriber(sf, email, source);
        String token = Hashing.randomToken();
        s.setTokenHash(Hashing.sha256Hex(token));
        subscribers.save(s);
        String store = mailer.storeName(sf);
        mailer.sendTransactional(sf, email, "Confirm your " + store + " deals email",
                "Hi,\n\nPlease confirm that you want " + store + "'s best deals by email (about once a week):\n"
                        + mailer.siteUrl("/api/newsletter/confirm?t=" + token)
                        + "\n\nDidn't ask for this? Just ignore this email and you won't hear from us.", "NEWSLETTER_CONFIRM");
        return new SignupResult("CHECK_EMAIL", null);
    }

    @Transactional
    public LinkResult confirmSubscription(String token) {
        Optional<NewsletterSubscriber> s = token == null ? Optional.empty() : subscribers.findByTokenHash(Hashing.sha256Hex(token));
        if (s.isEmpty()) return new LinkResult(null, null, false);
        s.get().setConfirmedAt(Instant.now());
        s.get().setUnsubscribedAt(null);
        return new LinkResult(s.get().getStorefront(), null, true);
    }

    @Transactional
    public LinkResult unsubscribe(String token) {
        Optional<NewsletterSubscriber> s = token == null ? Optional.empty() : subscribers.findByTokenHash(Hashing.sha256Hex(token));
        if (s.isEmpty()) return new LinkResult(null, null, false);
        if (s.get().getUnsubscribedAt() == null) s.get().setUnsubscribedAt(Instant.now());
        return new LinkResult(s.get().getStorefront(), null, true);
    }

    /**
     * Weekly deals email for newsletter subscribers who are not already getting it as customers; at most {@code limit}
     * per run and once per {@code week}. Returns how many were sent.
     */
    @Transactional
    public int sendDigest(Storefront sf, String subject, String body, String week, Set<String> alreadyReached, int limit) {
        int sent = 0;
        for (NewsletterSubscriber s : subscribers.findByStorefrontAndConfirmedAtIsNotNullAndUnsubscribedAtIsNull(sf)) {
            if (sent >= limit) break;
            if (week.equals(s.getLastDigestWeek()) || alreadyReached.contains(s.getEmail())) continue;
            String token = Hashing.randomToken();
            s.setTokenHash(Hashing.sha256Hex(token));
            s.setLastDigestWeek(week);
            mailer.sendToSubscriber(sf, s.getEmail(), subject, body, "NEWSLETTER_DIGEST", mailer.siteUrl("/api/newsletter/unsubscribe?t=" + token));
            sent++;
        }
        return sent;
    }

    @Transactional(readOnly = true)
    public long subscriberCount(Storefront sf) {
        return subscribers.countByStorefrontAndConfirmedAtIsNotNullAndUnsubscribedAtIsNull(sf);
    }

    private static String email(String raw) {
        String e = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (e.length() > 254 || !EMAIL.matcher(e).matches()) {
            throw ApiException.badRequest("INVALID_EMAIL", "Enter a valid email address");
        }
        return e;
    }
}
