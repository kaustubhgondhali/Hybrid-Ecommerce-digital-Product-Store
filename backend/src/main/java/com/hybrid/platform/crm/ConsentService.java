package com.hybrid.platform.crm;

import com.hybrid.platform.common.AppProperties;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.crypto.CryptoService;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.identity.IdentityEvents;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Marketing email consent. Customers opt in themselves (sign-up checkbox or account page); staff cannot subscribe
 * anyone. Every marketing email carries a signed one-click unsubscribe link that works without signing in.
 */
@Service
public class ConsentService {

    private final CrmRepositories.ConsentRepository consents;
    private final CryptoService crypto;
    private final AppProperties props;

    public ConsentService(CrmRepositories.ConsentRepository consents, CryptoService crypto, AppProperties props) {
        this.consents = consents;
        this.crypto = crypto;
        this.props = props;
    }

    /** Runs inside the registration transaction, so the account and the consent are saved together. */
    @EventListener
    public void onOptIn(IdentityEvents.MarketingOptedIn e) {
        set(e.customerId(), e.storefront(), true, MarketingConsent.Source.SIGNUP);
    }

    /** Subscription state per storefront path ("shop", "digital"). */
    @Transactional(readOnly = true)
    public Map<String, Boolean> preferences(Long customerId) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (Storefront sf : Storefront.values()) out.put(sf.path(), false);
        consents.findByCustomerId(customerId).forEach(c -> out.put(c.getStorefront().path(), c.isSubscribed()));
        return out;
    }

    @Transactional
    public Map<String, Boolean> updatePreferences(Long customerId, Map<String, Boolean> changes) {
        if (changes != null) {
            changes.forEach((path, subscribed) -> {
                if (subscribed != null) set(customerId, Storefront.fromPath(path), subscribed, MarketingConsent.Source.ACCOUNT);
            });
        }
        return preferences(customerId);
    }

    @Transactional
    public Storefront unsubscribe(Long customerId, String storefrontPath, String token) {
        Storefront sf;
        try {
            sf = Storefront.fromPath(storefrontPath);
        } catch (ApiException e) {
            throw invalidLink();
        }
        if (customerId == null || !crypto.verify(payload(customerId, sf), token)) {
            throw invalidLink();
        }
        set(customerId, sf, false, MarketingConsent.Source.UNSUBSCRIBE_LINK);
        return sf;
    }

    @Transactional(readOnly = true)
    public boolean isSubscribed(Long customerId, Storefront sf) {
        return consents.findById(new MarketingConsent.Key(customerId, sf)).map(MarketingConsent::isSubscribed).orElse(false);
    }

    @Transactional(readOnly = true)
    public long subscriberCount(Storefront sf) {
        return consents.countByStorefrontAndSubscribedTrue(sf);
    }

    public String unsubscribeUrl(Long customerId, Storefront sf) {
        return base() + "/account/unsubscribe.html?c=" + customerId + "&sf=" + sf.path() + "&t=" + crypto.sign(payload(customerId, sf));
    }

    private void set(Long customerId, Storefront sf, boolean subscribed, MarketingConsent.Source source) {
        MarketingConsent c = consents.findById(new MarketingConsent.Key(customerId, sf)).orElse(null);
        if (c == null) {
            consents.save(new MarketingConsent(customerId, sf, subscribed, source));
        } else if (c.isSubscribed() != subscribed) {
            c.set(subscribed, source);
            consents.save(c);
        }
    }

    private static String payload(Long customerId, Storefront sf) {
        return "unsubscribe:" + customerId + ":" + sf.name();
    }

    private static ApiException invalidLink() {
        return ApiException.badRequest("UNSUBSCRIBE_LINK_INVALID", "This unsubscribe link is not valid. Sign in and change your email preferences in your account instead.");
    }

    private String base() {
        String b = props.baseUrl() == null ? "" : props.baseUrl();
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }
}
