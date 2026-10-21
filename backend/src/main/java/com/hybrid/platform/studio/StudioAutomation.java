package com.hybrid.platform.studio;

import com.hybrid.platform.catalog.CatalogEvents;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.identity.AdminRole;
import com.hybrid.platform.security.AdminPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * When a product goes live for the first time, creates the content the store asked for in the studio settings: a blog
 * post and a Web Story (as drafts or published) and a launch newsletter (always a draft). Each piece is made at most
 * once per product, and a failure in one does not stop the others.
 */
@Component
public class StudioAutomation {

    private static final Logger log = LoggerFactory.getLogger(StudioAutomation.class);

    private final ContentStudioService studio;

    public StudioAutomation(ContentStudioService studio) {
        this.studio = studio;
    }

    static AdminPrincipal system(Storefront sf) {
        return new AdminPrincipal(0L, sf, "automation", "Content automation", AdminRole.MASTER_ADMIN);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPublished(CatalogEvents.ProductPublished e) {
        StudioSettings s = studio.settingsEntity(e.storefront());
        if (s == null) return;
        AdminPrincipal bot = system(e.storefront());
        int variant = (int) (e.productId() % 3);
        if (s.getAutoBlog() != StudioSettings.Mode.OFF) {
            run(e, GeneratedContent.Kind.BLOG, () -> studio.createBlog(bot, e.productId(), s.getBlogKind(), variant,
                    s.getAutoBlog() == StudioSettings.Mode.PUBLISH, true));
        }
        if (s.getAutoStory() != StudioSettings.Mode.OFF) {
            run(e, GeneratedContent.Kind.STORY, () -> studio.createStory(bot, e.productId(), variant,
                    s.getAutoStory() == StudioSettings.Mode.PUBLISH, true));
        }
        if (s.isAutoNewsletter()) {
            run(e, GeneratedContent.Kind.NEWSLETTER, () -> studio.createNewsletter(bot, List.of(e.productId()),
                    ContentWriter.EmailKind.LAUNCH, null, variant, true));
        }
    }

    private void run(CatalogEvents.ProductPublished e, GeneratedContent.Kind kind, Runnable action) {
        try {
            if (studio.alreadyGenerated(e.storefront(), e.productId(), kind)) return;
            action.run();
        } catch (RuntimeException ex) {
            log.warn("Content studio could not create {} for product {} ({}): {}", kind, e.productId(), e.storefront(), ex.getMessage());
        }
    }
}
