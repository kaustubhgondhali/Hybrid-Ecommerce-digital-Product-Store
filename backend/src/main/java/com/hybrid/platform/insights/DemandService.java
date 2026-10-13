package com.hybrid.platform.insights;

import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.error.ApiException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.TextStyle;
import java.util.*;
import java.util.regex.Pattern;

/**
 * "Sales &amp; demand": which products sell and which shoppers want, for today, this week, this month, the last
 * 6 months or this year, compared with the period before. Built only from the store's own data: sales reported by
 * affiliate networks and paid orders, clicks to partner stores, wishlist and cart saves, price alerts, product page
 * views and site searches.
 */
@Service
public class DemandService {

    /** Demand points per shopper action (shown to the admin, so keep them simple). */
    static final int VIEW_POINTS = 1, SAVE_POINTS = 3, ALERT_POINTS = 3, CLICK_POINTS = 5, SALE_POINTS = 20;
    private static final int VIEWS = 0, CLICKS = 1, SAVES = 2, ALERTS = 3, SALES = 4, EARNED = 5;
    private static final String PAID_ORDER = "o.paid_at >= :from and o.paid_at < :to and o.status not in ('CANCELLED', 'EXPIRED', 'REFUNDED')";

    public enum Period {
        TODAY("today", "Today", "yesterday at the same time"),
        WEEK("week", "This week", "last week, same days"),
        MONTH("month", "This month", "last month, same days"),
        HALF_YEAR("6months", "Last 6 months", "the 6 months before"),
        YEAR("year", "This year", "last year, same dates");

        final String key;
        final String label;
        final String compareLabel;

        Period(String key, String label, String compareLabel) {
            this.key = key;
            this.label = label;
            this.compareLabel = compareLabel;
        }

        public static Period parse(String raw) {
            if (raw == null || raw.isBlank()) return MONTH;
            for (Period p : values()) if (p.key.equalsIgnoreCase(raw.trim())) return p;
            throw ApiException.badRequest("INVALID_PERIOD", "Choose today, week, month, 6months or year");
        }

        LocalDate start(LocalDate today) {
            return switch (this) {
                case TODAY -> today;
                case WEEK -> today.with(DayOfWeek.MONDAY);
                case MONTH -> today.withDayOfMonth(1);
                case HALF_YEAR -> today.withDayOfMonth(1).minusMonths(5);
                case YEAR -> today.withDayOfYear(1);
            };
        }

        ZonedDateTime back(ZonedDateTime t) {
            return switch (this) {
                case TODAY -> t.minusDays(1);
                case WEEK -> t.minusWeeks(1);
                case MONTH -> t.minusMonths(1);
                case HALF_YEAR -> t.minusMonths(6);
                case YEAR -> t.minusYears(1);
            };
        }
    }

    public record Totals(long views, long clicks, long saves, long alerts, long sales, Long earnedMinor, long searches) {
    }

    public record ProductRow(Long productId, String title, String slug, String category, boolean published,
                             long views, long clicks, long saves, long alerts, long sales, Long earnedMinor,
                             long score, long previousScore, Integer changePercent, boolean isNew) {
    }

    public record GroupRow(String name, String key, int products, long clicks, long sales, Long earnedMinor,
                           long score, long previousScore, Integer changePercent) {
    }

    public record SearchRow(String term, long searches, long noResults) {
    }

    public record Bucket(String label, Long views, long clicks, long sales) {
    }

    public record Report(String period, String periodLabel, String compareLabel, LocalDate from, LocalDate to,
                         Totals totals, Totals previous, LocalDate trackingSince, boolean showEarnings,
                         Map<String, Integer> points,
                         List<ProductRow> inDemand, List<ProductRow> bestSellers, List<ProductRow> rising,
                         List<GroupRow> categories, List<GroupRow> stores,
                         List<SearchRow> searches, List<SearchRow> notFound, List<Bucket> activity) {
    }

    /** One time range: exact instants for timestamped events, whole days (India time) for the daily counters. */
    private record Window(Instant from, Instant to, LocalDate fromDay, LocalDate toDay) {
        Map<String, Object> params(Storefront sf) {
            Map<String, Object> p = new HashMap<>();
            p.put("sf", sf.name());
            p.put("from", from);
            p.put("to", to);
            p.put("fromDay", fromDay);
            p.put("toDay", toDay);
            return p;
        }
    }

    private final EntityManager em;
    private final Clock clock;

    public DemandService(EntityManager em) {
        this.em = em;
        this.clock = Clock.system(DemandTracker.ZONE);
    }

    @Transactional(readOnly = true)
    public Report report(Storefront sf, Period period, boolean showEarnings) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(DemandTracker.ZONE));
        LocalDate today = now.toLocalDate();
        ZonedDateTime start = period.start(today).atStartOfDay(DemandTracker.ZONE);
        Window current = new Window(start.toInstant(), now.toInstant(), start.toLocalDate(), today);
        ZonedDateTime prevStart = period.back(start);
        ZonedDateTime prevEnd = period.back(now);
        Window previous = new Window(prevStart.toInstant(), prevEnd.toInstant(), prevStart.toLocalDate(),
                period == Period.TODAY ? prevStart.toLocalDate() : prevEnd.toLocalDate());

        Map<Long, long[]> nowMetrics = productMetrics(sf, current);
        Map<Long, long[]> before = productMetrics(sf, previous);
        Set<Long> ids = new HashSet<>(nowMetrics.keySet());
        ids.addAll(before.keySet());
        Map<Long, Object[]> info = productInfo(sf, ids);

        List<ProductRow> rows = new ArrayList<>();
        for (Map.Entry<Long, long[]> e : nowMetrics.entrySet()) {
            long[] m = e.getValue();
            long score = score(m);
            if (score == 0) continue;
            long prev = score(before.getOrDefault(e.getKey(), new long[6]));
            Object[] p = info.getOrDefault(e.getKey(), new Object[5]);
            rows.add(new ProductRow(e.getKey(), str(p[1], "Deleted product"), str(p[2], null), str(p[4], "No category"),
                    "PUBLISHED".equals(p[3]), m[VIEWS], m[CLICKS], m[SAVES], m[ALERTS], m[SALES],
                    showEarnings ? m[EARNED] : null, score, prev, change(score, prev), prev == 0));
        }
        rows.sort(Comparator.comparingLong(ProductRow::score).thenComparingLong(ProductRow::clicks).reversed()
                .thenComparing(ProductRow::title));
        List<ProductRow> bestSellers = rows.stream().filter(r -> r.sales() > 0)
                .sorted(Comparator.comparingLong(ProductRow::sales)
                        .thenComparingLong(r -> r.earnedMinor() == null ? 0 : r.earnedMinor()).reversed())
                .limit(10).toList();
        List<ProductRow> rising = rows.stream()
                .filter(r -> r.score() >= 5 && (r.previousScore() == 0 || r.score() * 4 >= r.previousScore() * 5))
                .sorted(Comparator.comparingLong((ProductRow r) -> r.score() - r.previousScore()).reversed())
                .limit(10).toList();

        return new Report(period.key, period.label, period.compareLabel, current.fromDay(), current.toDay(),
                totals(sf, current, nowMetrics, showEarnings), totals(sf, previous, before, showEarnings),
                trackingSince(sf), showEarnings,
                Map.of("view", VIEW_POINTS, "save", SAVE_POINTS, "alert", ALERT_POINTS, "click", CLICK_POINTS, "sale", SALE_POINTS),
                rows.size() > 25 ? List.copyOf(rows.subList(0, 25)) : rows, bestSellers, rising,
                categories(rows, before, info, showEarnings), stores(sf, current, previous, showEarnings),
                searches(sf, current, false), searches(sf, current, true), activity(sf, period, current, now));
    }

    // ------------------------------------------------------------------ per product

    private Map<Long, long[]> productMetrics(Storefront sf, Window w) {
        Map<String, Object> p = w.params(sf);
        Map<Long, long[]> out = new HashMap<>();
        add(out, p, "select product_id, sum(views) from product_view_days "
                + "where storefront = :sf and view_date >= :fromDay and view_date <= :toDay group by product_id", VIEWS);
        add(out, p, "select product_id, count(*) from affiliate_clicks where storefront = :sf and suspected_bot = false "
                + "and created_at >= :from and created_at < :to group by product_id", CLICKS);
        add(out, p, "select product_id, count(*) from wishlist_items "
                + "where storefront = :sf and created_at >= :from and created_at < :to group by product_id", SAVES);
        add(out, p, "select i.product_id, count(*) from cart_items i join carts c on c.id = i.cart_id "
                + "where c.storefront = :sf and i.added_at >= :from and i.added_at < :to group by i.product_id", SAVES);
        add(out, p, "select product_id, count(*) from price_alerts "
                + "where storefront = :sf and confirmed_at >= :from and confirmed_at < :to group by product_id", ALERTS);
        add(out, p, "select k.product_id, count(*), coalesce(sum(v.commission_minor), 0) from affiliate_conversions v "
                + "join affiliate_clicks k on k.click_ref = v.click_ref where v.storefront = :sf and v.status <> 'REVERSED' "
                + "and v.reported_at >= :from and v.reported_at < :to group by k.product_id", SALES, EARNED);
        add(out, p, "select l.product_id, coalesce(sum(l.quantity), 0), coalesce(sum(l.line_total_minor), 0) "
                + "from order_lines l join orders o on o.id = l.order_id where o.storefront = :sf and " + PAID_ORDER
                + " group by l.product_id", SALES, EARNED);
        return out;
    }

    /** Adds columns 2.. of each row to the product's metrics at the given positions. */
    private void add(Map<Long, long[]> out, Map<String, Object> params, String sql, int... metrics) {
        for (Object[] row : rows(sql, params)) {
            long[] m = out.computeIfAbsent(num(row[0]), k -> new long[6]);
            for (int i = 0; i < metrics.length; i++) m[metrics[i]] += num(row[i + 1]);
        }
    }

    /** id, title, slug, status, category name. */
    private Map<Long, Object[]> productInfo(Storefront sf, Set<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<Long, Object[]> out = new HashMap<>();
        for (Object[] row : rows("select p.id, p.title, p.slug, p.status, c.name from products p "
                + "left join categories c on c.id = p.category_id where p.storefront = :sf and p.id in (:ids)",
                Map.of("sf", sf.name(), "ids", ids))) {
            out.put(num(row[0]), row);
        }
        return out;
    }

    static long score(long[] m) {
        return m[VIEWS] * VIEW_POINTS + m[SAVES] * SAVE_POINTS + m[ALERTS] * ALERT_POINTS
                + m[CLICKS] * CLICK_POINTS + m[SALES] * SALE_POINTS;
    }

    static Integer change(long now, long before) {
        if (before == 0) return null;
        return (int) Math.round((now - before) * 100.0 / before);
    }

    // ------------------------------------------------------------------ totals, groups, searches

    private Totals totals(Storefront sf, Window w, Map<Long, long[]> metrics, boolean showEarnings) {
        Map<String, Object> p = w.params(sf);
        long views = 0, clicks = 0, saves = 0, alerts = 0;
        for (long[] m : metrics.values()) {
            views += m[VIEWS];
            clicks += m[CLICKS];
            saves += m[SAVES];
            alerts += m[ALERTS];
        }
        // every reported sale, also those a network could not tie to one of our clicks
        Object[] affiliate = first(rows("select count(*), coalesce(sum(commission_minor), 0) from affiliate_conversions "
                + "where storefront = :sf and status <> 'REVERSED' and reported_at >= :from and reported_at < :to", p), 2);
        Object[] direct = first(rows("select coalesce(sum(l.quantity), 0), coalesce(sum(l.line_total_minor), 0) from order_lines l "
                + "join orders o on o.id = l.order_id where o.storefront = :sf and " + PAID_ORDER, p), 2);
        Object[] searches = first(rows("select coalesce(sum(searches), 0) from search_term_days "
                + "where storefront = :sf and search_date >= :fromDay and search_date <= :toDay", p), 1);
        return new Totals(views, clicks, saves, alerts, num(affiliate[0]) + num(direct[0]),
                showEarnings ? num(affiliate[1]) + num(direct[1]) : null, num(searches[0]));
    }

    private static List<GroupRow> categories(List<ProductRow> rows, Map<Long, long[]> before,
                                             Map<Long, Object[]> info, boolean showEarnings) {
        Map<String, long[]> groups = new LinkedHashMap<>();
        for (ProductRow r : rows) {
            long[] g = groups.computeIfAbsent(r.category(), k -> new long[6]);
            g[0]++;
            g[1] += r.clicks();
            g[2] += r.sales();
            g[3] += r.earnedMinor() == null ? 0 : r.earnedMinor();
            g[4] += r.score();
        }
        before.forEach((id, m) -> {
            Object[] p = info.getOrDefault(id, new Object[5]);
            long[] g = groups.get(str(p[4], "No category"));
            if (g != null) g[5] += score(m);
        });
        return groups.entrySet().stream()
                .map(e -> new GroupRow(e.getKey(), e.getKey(), (int) e.getValue()[0], e.getValue()[1], e.getValue()[2],
                        showEarnings ? e.getValue()[3] : null, e.getValue()[4], e.getValue()[5], change(e.getValue()[4], e.getValue()[5])))
                .sorted(Comparator.comparingLong(GroupRow::score).reversed())
                .limit(12).toList();
    }

    /** Partner stores by clicks and reported sales. */
    private List<GroupRow> stores(Storefront sf, Window current, Window previous, boolean showEarnings) {
        String clicksSql = "select l.merchant_id, count(*) from affiliate_clicks c join affiliate_links l on l.id = c.link_id "
                + "where c.storefront = :sf and c.suspected_bot = false and c.created_at >= :from and c.created_at < :to group by l.merchant_id";
        String salesSql = "select merchant_id, count(*), coalesce(sum(commission_minor), 0) from affiliate_conversions "
                + "where storefront = :sf and status <> 'REVERSED' and merchant_id is not null "
                + "and reported_at >= :from and reported_at < :to group by merchant_id";
        // 0 clicks, 1 sales, 2 commission, 3 clicks before, 4 sales before
        Map<Long, long[]> m = new HashMap<>();
        for (Object[] r : rows(clicksSql, current.params(sf))) m.computeIfAbsent(num(r[0]), k -> new long[5])[0] += num(r[1]);
        for (Object[] r : rows(salesSql, current.params(sf))) {
            long[] v = m.computeIfAbsent(num(r[0]), k -> new long[5]);
            v[1] += num(r[1]);
            v[2] += num(r[2]);
        }
        if (m.isEmpty()) return List.of();
        for (Object[] r : rows(clicksSql, previous.params(sf))) {
            long[] v = m.get(num(r[0]));
            if (v != null) v[3] += num(r[1]);
        }
        for (Object[] r : rows(salesSql, previous.params(sf))) {
            long[] v = m.get(num(r[0]));
            if (v != null) v[4] += num(r[1]);
        }
        Map<Long, Object[]> names = new HashMap<>();
        for (Object[] r : rows("select id, name, domain from affiliate_merchants where id in (:ids)", Map.of("ids", m.keySet()))) {
            names.put(num(r[0]), r);
        }
        return m.entrySet().stream().map(e -> {
                    long[] v = e.getValue();
                    Object[] n = names.getOrDefault(e.getKey(), new Object[]{null, "Removed store", null});
                    long score = v[0] * CLICK_POINTS + v[1] * SALE_POINTS;
                    long prev = v[3] * CLICK_POINTS + v[4] * SALE_POINTS;
                    return new GroupRow(str(n[1], "Removed store"), str(n[2], null), 0, v[0], v[1],
                            showEarnings ? v[2] : null, score, prev, change(score, prev));
                })
                .sorted(Comparator.comparingLong(GroupRow::score).reversed())
                .limit(12).toList();
    }

    private List<SearchRow> searches(Storefront sf, Window w, boolean notFoundOnly) {
        String sql = "select term, sum(searches), sum(no_results) from search_term_days "
                + "where storefront = :sf and search_date >= :fromDay and search_date <= :toDay group by term "
                + (notFoundOnly ? "having sum(no_results) > 0 order by sum(no_results) desc, term" : "order by sum(searches) desc, term");
        Query q = query(sql, w.params(sf));
        q.setMaxResults(15);
        return list(q).stream().map(r -> new SearchRow(str(r[0], ""), num(r[1]), num(r[2]))).toList();
    }

    private LocalDate trackingSince(Storefront sf) {
        LocalDate a = date(first(rows("select min(view_date) from product_view_days where storefront = :sf", Map.of("sf", sf.name())), 1)[0]);
        LocalDate b = date(first(rows("select min(search_date) from search_term_days where storefront = :sf", Map.of("sf", sf.name())), 1)[0]);
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    // ------------------------------------------------------------------ activity over time

    /** Hours for today, days for a week or month, months for 6 months or a year. */
    private List<Bucket> activity(Storefront sf, Period period, Window w, ZonedDateTime now) {
        List<String> labels = new ArrayList<>();
        Map<String, Object> p = w.params(sf);
        StringBuilder clickCols = new StringBuilder();
        StringBuilder saleCols = new StringBuilder();
        StringBuilder orderCols = new StringBuilder();
        StringBuilder viewCols = new StringBuilder();
        ZonedDateTime cursor = w.from().atZone(DemandTracker.ZONE);
        for (int i = 0; cursor.isBefore(now); i++) {
            ZonedDateTime next = switch (period) {
                case TODAY -> cursor.plusHours(1);
                case WEEK, MONTH -> cursor.plusDays(1);
                case HALF_YEAR, YEAR -> cursor.plusMonths(1);
            };
            labels.add(switch (period) {
                case TODAY -> String.format("%02d:00", cursor.getHour());
                case WEEK -> cursor.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + cursor.getDayOfMonth();
                case MONTH -> cursor.getDayOfMonth() + " " + cursor.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
                case HALF_YEAR, YEAR -> cursor.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            });
            p.put("f" + i, cursor.toInstant());
            p.put("t" + i, (next.isAfter(now) ? now : next).toInstant());
            p.put("d" + i, cursor.toLocalDate());
            p.put("e" + i, next.toLocalDate().minusDays(1));
            String sep = i == 0 ? "" : ", ";
            clickCols.append(sep).append("sum(case when created_at >= :f").append(i).append(" and created_at < :t").append(i).append(" then 1 else 0 end)");
            saleCols.append(sep).append("sum(case when reported_at >= :f").append(i).append(" and reported_at < :t").append(i).append(" then 1 else 0 end)");
            orderCols.append(sep).append("sum(case when o.paid_at >= :f").append(i).append(" and o.paid_at < :t").append(i).append(" then l.quantity else 0 end)");
            viewCols.append(sep).append("sum(case when view_date >= :d").append(i).append(" and view_date <= :e").append(i).append(" then views else 0 end)");
            cursor = next;
        }
        int n = labels.size();
        if (n == 0) return List.of();
        Object[] clicks = first(rows("select " + clickCols + " from affiliate_clicks where storefront = :sf and suspected_bot = false "
                + "and created_at >= :from and created_at < :to", p), n);
        Object[] sales = first(rows("select " + saleCols + " from affiliate_conversions where storefront = :sf and status <> 'REVERSED' "
                + "and reported_at >= :from and reported_at < :to", p), n);
        Object[] orders = first(rows("select " + orderCols + " from order_lines l join orders o on o.id = l.order_id "
                + "where o.storefront = :sf and " + PAID_ORDER, p), n);
        Object[] views = period == Period.TODAY ? null : first(rows("select " + viewCols + " from product_view_days "
                + "where storefront = :sf and view_date >= :fromDay and view_date <= :toDay", p), n);
        List<Bucket> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new Bucket(labels.get(i), views == null ? null : num(views[i]), num(clicks[i]), num(sales[i]) + num(orders[i])));
        }
        return out;
    }

    // ------------------------------------------------------------------ query helpers

    /**
     * Native SQL through JPA so that instants are bound exactly as the entities stored them
     * (hibernate.jdbc.time_zone), on MySQL and H2 alike. Only the parameters a statement uses are bound.
     */
    private Query query(String sql, Map<String, Object> params) {
        Query q = em.createNativeQuery(sql);
        params.forEach((name, value) -> {
            if (Pattern.compile(":" + name + "\\b").matcher(sql).find()) q.setParameter(name, value);
        });
        return q;
    }

    private List<Object[]> rows(String sql, Map<String, Object> params) {
        return list(query(sql, params));
    }

    private static List<Object[]> list(Query q) {
        List<?> raw = q.getResultList();
        List<Object[]> out = new ArrayList<>(raw.size());
        for (Object o : raw) out.add(o instanceof Object[] a ? a : new Object[]{o});
        return out;
    }

    private static Object[] first(List<Object[]> rows, int columns) {
        return rows.isEmpty() ? new Object[columns] : rows.get(0);
    }

    private static long num(Object v) {
        return v instanceof Number n ? n.longValue() : 0;
    }

    private static LocalDate date(Object v) {
        if (v instanceof LocalDate d) return d;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        return null;
    }

    private static String str(Object v, String fallback) {
        return v == null ? fallback : v.toString();
    }
}
