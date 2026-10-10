package com.hybrid.platform.catalog;

import com.hybrid.platform.catalog.CatalogEnums.ProductType;
import com.hybrid.platform.catalog.CatalogEnums.SellingMethod;
import com.hybrid.platform.common.Storefront;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Relevance-ranked product search using the MySQL FULLTEXT index created by the vendor migration. On other
 * databases (H2 in tests) or when the index is missing, {@link #rankedIds} returns empty and callers use the
 * portable LIKE search instead.
 */
@Component
public class ProductSearch {

    private static final Logger log = LoggerFactory.getLogger(ProductSearch.class);
    static final int MAX_RESULTS = 500;
    private static final Pattern BOOLEAN_OPERATORS = Pattern.compile("[+\\-<>()~*\"@]");

    private final JdbcTemplate jdbc;
    private volatile Boolean available;

    public ProductSearch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean available() {
        Boolean a = available;
        if (a == null) {
            try {
                Integer n = jdbc.queryForObject("""
                        select count(*) from information_schema.STATISTICS
                        where TABLE_SCHEMA = database() and TABLE_NAME = 'products' and INDEX_NAME = 'ft_products_search'
                        """, Integer.class);
                a = n != null && n > 0;
            } catch (RuntimeException e) {
                a = false;
            }
            available = a;
            log.info("Product search uses {}", a ? "the MySQL FULLTEXT index" : "LIKE matching");
        }
        return a;
    }

    /**
     * Published product ids matching {@code query}, best match first, or empty when full-text search is not
     * available or finds nothing (very short words and stop words are not indexed by MySQL).
     */
    public Optional<List<Long>> rankedIds(Storefront sf, String query, Long categoryId, ProductType type, SellingMethod method) {
        if (query == null || !available()) return Optional.empty();
        String terms = BOOLEAN_OPERATORS.matcher(query).replaceAll(" ").trim();
        if (terms.isEmpty()) return Optional.empty();
        StringBuilder sql = new StringBuilder("""
                select id from products
                where storefront = ? and status = 'PUBLISHED'
                  and match(title, summary, description, brand, focus_keyword) against (? in natural language mode)
                """);
        List<Object> args = new ArrayList<>(List.of(sf.name(), terms));
        if (categoryId != null) { sql.append(" and category_id = ?"); args.add(categoryId); }
        if (type != null) { sql.append(" and product_type = ?"); args.add(type.name()); }
        if (method != null) { sql.append(" and selling_method = ?"); args.add(method.name()); }
        sql.append(" order by match(title, summary, description, brand, focus_keyword) against (? in natural language mode) desc, published_at desc limit ")
                .append(MAX_RESULTS);
        args.add(terms);
        try {
            List<Long> ids = jdbc.queryForList(sql.toString(), Long.class, args.toArray());
            return ids.isEmpty() ? Optional.empty() : Optional.of(ids);
        } catch (RuntimeException e) {
            log.warn("Full-text search failed, falling back to LIKE: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
