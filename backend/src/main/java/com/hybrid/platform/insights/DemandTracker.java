package com.hybrid.platform.insights;

import com.hybrid.platform.affiliate.AffiliateService;
import com.hybrid.platform.common.Storefront;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Counts product page views and site searches per day (India time) for the Sales &amp; demand report. Only daily
 * totals are stored, nothing about the visitor. Bots are ignored. Recording never fails the shopper's request.
 */
@Component
public class DemandTracker {

    static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final Logger log = LoggerFactory.getLogger(DemandTracker.class);
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private final JdbcTemplate jdbc;

    public DemandTracker(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A shopper opened a published product's page. */
    public void productViewed(Storefront sf, String slug, String userAgent) {
        if (slug == null || AffiliateService.isBot(userAgent)) return;
        try {
            List<Long> ids = jdbc.queryForList("select id from products where storefront = ? and slug = ? and status = 'PUBLISHED'",
                    Long.class, sf.name(), slug);
            if (ids.isEmpty()) return;
            LocalDate day = LocalDate.now(ZONE);
            bump("update product_view_days set views = views + 1 where product_id = ? and view_date = ?",
                    new Object[]{ids.get(0), day},
                    "insert into product_view_days (product_id, view_date, storefront, views) values (?, ?, ?, 1)",
                    new Object[]{ids.get(0), day, sf.name()});
        } catch (RuntimeException e) {
            log.warn("Could not count a product view: {}", e.getMessage());
        }
    }

    /** A shopper searched the catalog; {@code results} is how many products matched. */
    public void searched(Storefront sf, String query, long results, String userAgent) {
        String term = normalize(query);
        if (term == null || AffiliateService.isBot(userAgent)) return;
        int none = results == 0 ? 1 : 0;
        LocalDate day = LocalDate.now(ZONE);
        try {
            bump("update search_term_days set searches = searches + 1, no_results = no_results + ? "
                            + "where storefront = ? and term = ? and search_date = ?",
                    new Object[]{none, sf.name(), term, day},
                    "insert into search_term_days (storefront, term, search_date, searches, no_results) values (?, ?, ?, 1, ?)",
                    new Object[]{sf.name(), term, day, none});
        } catch (RuntimeException e) {
            log.warn("Could not count a search: {}", e.getMessage());
        }
    }

    /** Lower case, single spaces, 2 to 100 characters; null when there is nothing worth counting. */
    static String normalize(String query) {
        if (query == null) return null;
        String t = SPACES.matcher(query.trim().toLowerCase(Locale.ROOT)).replaceAll(" ");
        if (t.length() < 2) return null;
        return t.length() > 100 ? t.substring(0, 100).trim() : t;
    }

    /** Portable "add one to today's row": update, else insert; a concurrent insert falls back to the update. */
    private void bump(String update, Object[] updateArgs, String insert, Object[] insertArgs) {
        if (jdbc.update(update, updateArgs) > 0) return;
        try {
            jdbc.update(insert, insertArgs);
        } catch (DuplicateKeyException race) {
            jdbc.update(update, updateArgs);
        }
    }
}
