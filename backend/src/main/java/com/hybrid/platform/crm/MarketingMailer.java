package com.hybrid.platform.crm;

import com.hybrid.platform.common.AppProperties;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.commerce.settings.SettingsRepositories;
import com.hybrid.platform.commerce.settings.StoreSettings;
import com.hybrid.platform.notification.MailService;
import org.springframework.stereotype.Component;

/** Sends marketing email through the storefront's outbox, always with the reason and an unsubscribe link. */
@Component
public class MarketingMailer {

    private final MailService mail;
    private final ConsentService consents;
    private final SettingsRepositories.StoreSettingsRepository stores;
    private final AppProperties props;

    public MarketingMailer(MailService mail, ConsentService consents, SettingsRepositories.StoreSettingsRepository stores,
                           AppProperties props) {
        this.mail = mail;
        this.consents = consents;
        this.stores = stores;
        this.props = props;
    }

    public void send(Storefront sf, Long customerId, String email, String subject, String body, String kind) {
        mail.send(sf, email, oneLine(subject), body + footer(sf, consents.unsubscribeUrl(customerId, sf)), kind);
    }

    /** Emails the visitor asked for (confirmations, price alerts): no marketing footer. */
    public void sendTransactional(Storefront sf, String to, String subject, String body, String kind) {
        mail.send(sf, to, oneLine(subject), body, kind);
    }

    /** Newsletter subscribers (no account): same footer with their own one-click unsubscribe link. */
    public void sendToSubscriber(Storefront sf, String email, String subject, String body, String kind, String unsubscribeUrl) {
        mail.send(sf, email, oneLine(subject), body + footer(sf, unsubscribeUrl), kind);
    }

    /** Absolute link on this site, e.g. {@code siteUrl("/api/newsletter/confirm?t=...")}. */
    public String siteUrl(String path) {
        String b = props.baseUrl() == null ? "" : props.baseUrl();
        return (b.endsWith("/") ? b.substring(0, b.length() - 1) : b) + path;
    }

    /** A staff preview: same footer, but without a working unsubscribe link. */
    public void sendPreview(Storefront sf, String email, String subject, String body) {
        mail.send(sf, email, oneLine(subject), body + footer(sf, "(each recipient gets their personal link here)"), "CAMPAIGN_TEST");
    }

    private String footer(Storefront sf, String unsubscribe) {
        return "\n\n--\nYou are receiving this because you subscribed to offers from " + storeName(sf) + ".\n"
                + "Unsubscribe with one click: " + unsubscribe;
    }

    public boolean smtpReady(Storefront sf) {
        MailService.SmtpView s = mail.get(sf);
        return s.enabled() && s.host() != null && s.port() != null && s.fromEmail() != null;
    }

    public String storeName(Storefront sf) {
        return stores.findById(sf).map(StoreSettings::getStoreName).filter(n -> !n.isBlank()).orElse(sf.path());
    }

    public String storeUrl(Storefront sf) {
        String b = props.baseUrl() == null ? "" : props.baseUrl();
        return (b.endsWith("/") ? b.substring(0, b.length() - 1) : b) + "/" + sf.path();
    }

    public String productUrl(Storefront sf, String slug) {
        return storeUrl(sf) + "/p/" + java.net.URLEncoder.encode(slug, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** "₹1,299.00" with Indian digit grouping. */
    public static String rupees(long minor) {
        java.text.NumberFormat f = java.text.NumberFormat.getCurrencyInstance(java.util.Locale.of("en", "IN"));
        return f.format(minor / 100.0);
    }

    static String oneLine(String s) {
        String v = s == null ? "" : s.replaceAll("[\\r\\n]+", " ").trim();
        return v.length() > 250 ? v.substring(0, 247) + "..." : v;
    }
}
