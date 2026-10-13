package com.hybrid.platform.crm;

import com.hybrid.platform.affiliate.AffiliateEvents;
import com.hybrid.platform.catalog.CatalogEvents;
import com.hybrid.platform.catalog.CatalogPublicService;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.crypto.CryptoService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.List;

/**
 * Posts new deals and price drops to the store's Telegram channel, once each, after the change is saved. Posts link to
 * the product page on this site (never straight to the partner), and stores whose prices must stay hidden never get
 * price-drop posts (those events are not raised for them).
 */
@Component
public class TelegramPoster {

    private final CrmRepositories.AutomationSettingsRepository settings;
    private final TelegramService telegram;
    private final CryptoService crypto;
    private final CrmQueries queries;
    private final MarketingMailer mailer;
    private final CatalogPublicService catalog;

    public TelegramPoster(CrmRepositories.AutomationSettingsRepository settings, TelegramService telegram, CryptoService crypto,
                          CrmQueries queries, MarketingMailer mailer, CatalogPublicService catalog) {
        this.settings = settings;
        this.telegram = telegram;
        this.crypto = crypto;
        this.queries = queries;
        this.mailer = mailer;
        this.catalog = catalog;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPublished(CatalogEvents.ProductPublished e) {
        CrmAutomationSettings s = ready(e.storefront());
        if (s == null || !s.isTelegramPostNew()) return;
        CatalogPublicService.ProductCard card = card(e.storefront(), e.productId());
        if (card == null || !queries.claimAutomation(e.storefront(), CrmAutomationService.STORE, "TG_NEW", "product:" + e.productId(), Instant.now())) {
            return;
        }
        post(s, "New deal: " + card.title() + "\n" + CrmAutomationService.priceLine(card) + "\n"
                + mailer.productUrl(e.storefront(), card.slug()));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPriceDropped(AffiliateEvents.PriceDropped e) {
        CrmAutomationSettings s = ready(e.storefront());
        if (s == null || !s.isTelegramPostDrops()) return;
        CatalogPublicService.ProductCard card = card(e.storefront(), e.productId());
        if (card == null || !queries.claimAutomation(e.storefront(), CrmAutomationService.STORE, "TG_DROP",
                "p" + e.productId() + ":" + e.newPriceMinor(), Instant.now())) {
            return;
        }
        long pct = (e.oldPriceMinor() - e.newPriceMinor()) * 100 / e.oldPriceMinor();
        post(s, "Price drop: " + e.productTitle() + "\nNow " + MarketingMailer.rupees(e.newPriceMinor()) + " (was "
                + MarketingMailer.rupees(e.oldPriceMinor()) + ", -" + pct + "%) at " + e.merchantName() + "\n"
                + mailer.productUrl(e.storefront(), card.slug()));
    }

    private CrmAutomationSettings ready(Storefront sf) {
        return settings.findById(sf)
                .filter(s -> s.isTelegramEnabled() && s.getTelegramTokenEnc() != null && s.getTelegramChatId() != null)
                .orElse(null);
    }

    private CatalogPublicService.ProductCard card(Storefront sf, Long productId) {
        return catalog.cards(sf, List.of(productId)).stream().findFirst().orElse(null);
    }

    private void post(CrmAutomationSettings s, String text) {
        telegram.send(crypto.decrypt(s.getTelegramTokenEnc()), s.getTelegramChatId(), text);
    }
}
