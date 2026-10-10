package com.hybrid.platform.catalog;

import com.hybrid.platform.storage.FileStorage;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/** Adds the product-page rendition to large images uploaded before it existed. Runs once per start, in the background. */
@Component
public class ImageRenditionBackfill {

    private static final Logger log = LoggerFactory.getLogger(ImageRenditionBackfill.class);
    private static final int MAX_PER_START = 500;

    private final EntityManager em;
    private final ImageOptimizer optimizer;
    private final FileStorage storage;
    private final TransactionTemplate tx;

    public ImageRenditionBackfill(EntityManager em, ImageOptimizer optimizer, FileStorage storage, TransactionTemplate tx) {
        this.em = em;
        this.optimizer = optimizer;
        this.storage = storage;
        this.tx = tx;
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        List<Long> ids;
        try {
            ids = em.createQuery("""
                            select i.id from ProductImage i
                            where i.largeKey is null and i.width > :w order by i.id""", Long.class)
                    .setParameter("w", ImageOptimizer.LARGE_WIDTH).setMaxResults(MAX_PER_START).getResultList();
        } catch (RuntimeException e) {
            log.warn("Image rendition backfill skipped: {}", e.getMessage());
            return;
        }
        int done = 0;
        for (Long id : ids) {
            try {
                String[] replacedThumb = new String[1];
                Boolean ok = tx.execute(status -> {
                    ProductImage img = em.find(ProductImage.class, id);
                    if (img == null || img.getLargeKey() != null) return false;
                    ImageOptimizer.Result r = optimizer.renditions(img.getStorageKey());
                    if (r.largeKey() == null) return false;
                    replacedThumb[0] = img.getThumbKey();
                    img.setRenditions(r.thumbKey(), r.largeKey(), r.width(), r.height());
                    return true;
                });
                if (Boolean.TRUE.equals(ok)) {
                    done++;
                    if (replacedThumb[0] != null) storage.delete(FileStorage.Area.PUBLIC, replacedThumb[0]);
                }
            } catch (RuntimeException e) {
                log.warn("Image rendition backfill failed for image {}: {}", id, e.getMessage());
            }
        }
        if (done > 0) log.info("Created product-page renditions for {} existing images", done);
    }
}
