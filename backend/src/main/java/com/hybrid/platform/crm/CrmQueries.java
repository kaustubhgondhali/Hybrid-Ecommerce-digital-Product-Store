package com.hybrid.platform.crm;

import com.hybrid.platform.common.Storefront;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Read-side SQL for the CRM. Aggregates each module's tables per customer for one storefront.
 * Timestamps are stored as UTC wall-clock DATETIME values (hibernate.jdbc.time_zone=UTC), so they are read and
 * bound as {@link LocalDateTime} in UTC. Sized for small and medium stores: segments are computed in memory.
 */
@Component
public class CrmQueries {

    /** Orders that represent a real purchase (cash-on-delivery orders count once confirmed). */
    static final List<String> PURCHASE_STATUSES = List.of("CONFIRMED", "PAID", "SHIPPED", "DELIVERED", "COMPLETED", "PARTIALLY_REFUNDED");
    /** Partner sales that were not reversed. */
    static final List<String> COUNTED_CONVERSIONS = List.of("PENDING", "APPROVED", "PAID");

    private static final String SNAPSHOT_SQL = """
            select c.id, c.email, c.full_name, c.phone, c.status, c.created_at, c.signup_storefront,
                   o.n as orders, o.revenue, o.first_at as first_order_at, o.last_at as last_order_at,
                   ct.items as cart_items, ct.last_at as cart_updated_at,
                   w.items as wishlist_items, w.last_at as wishlist_updated_at,
                   k.n as clicks, k.last_at as last_click_at,
                   v.n as conversions, v.commission, v.sales, v.last_at as last_conversion_at,
                   r.n as reviews,
                   mc.subscribed
            from customers c
              left join (select customer_id, count(*) as n, sum(total_minor) as revenue, min(placed_at) as first_at, max(placed_at) as last_at
                         from orders where storefront = :sf and status in (:purchase) group by customer_id) o on o.customer_id = c.id
              left join (select ca.customer_id, sum(ci.quantity) as items, max(ci.added_at) as last_at
                         from carts ca join cart_items ci on ci.cart_id = ca.id
                         where ca.storefront = :sf group by ca.customer_id) ct on ct.customer_id = c.id
              left join (select customer_id, count(*) as items, max(created_at) as last_at
                         from wishlist_items where storefront = :sf group by customer_id) w on w.customer_id = c.id
              left join (select customer_id, count(*) as n, max(created_at) as last_at
                         from affiliate_clicks where storefront = :sf and customer_id is not null and suspected_bot = false
                         group by customer_id) k on k.customer_id = c.id
              left join (select ac.customer_id, count(*) as n, sum(cv.commission_minor) as commission,
                                sum(cv.sale_amount_minor) as sales, max(cv.reported_at) as last_at
                         from affiliate_conversions cv join affiliate_clicks ac on ac.click_ref = cv.click_ref
                         where cv.storefront = :sf and cv.status in (:conversion) and ac.customer_id is not null
                         group by ac.customer_id) v on v.customer_id = c.id
              left join (select customer_id, count(*) as n from product_reviews where storefront = :sf group by customer_id) r on r.customer_id = c.id
              left join marketing_consents mc on mc.customer_id = c.id and mc.storefront = :sf
            where (c.signup_storefront = :sf or o.customer_id is not null or ct.customer_id is not null or w.customer_id is not null
                   or k.customer_id is not null or v.customer_id is not null or r.customer_id is not null or mc.customer_id is not null)
            """;

    public record TimelineEvent(String type, Instant at, String title, String detail, Long amountMinor, Long productId, String ref) {
    }

    public record Line(String title, String slug, int quantity) {
    }

    public record CampaignResults(long clicks, long clickers, long conversions, long commissionMinor, long orders,
                                  long revenueMinor, long couponOrders) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public CrmQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ snapshots

    public List<CustomerSnapshot> snapshots(Storefront sf) {
        return jdbc.query(SNAPSHOT_SQL, base(sf), (rs, i) -> snapshot(rs, sf));
    }

    public Optional<CustomerSnapshot> snapshot(Storefront sf, Long customerId) {
        return jdbc.query(SNAPSHOT_SQL + " and c.id = :id", base(sf).addValue("id", customerId), (rs, i) -> snapshot(rs, sf))
                .stream().findFirst();
    }

    private static MapSqlParameterSource base(Storefront sf) {
        return new MapSqlParameterSource("sf", sf.name())
                .addValue("purchase", PURCHASE_STATUSES)
                .addValue("conversion", COUNTED_CONVERSIONS);
    }

    private static CustomerSnapshot snapshot(ResultSet rs, Storefront sf) throws SQLException {
        Object subscribed = rs.getObject("subscribed");
        return new CustomerSnapshot(
                rs.getLong("id"), rs.getString("email"), rs.getString("full_name"), rs.getString("phone"),
                "ACTIVE".equals(rs.getString("status")),
                time(rs, "created_at"), sf.name().equals(rs.getString("signup_storefront")),
                rs.getLong("orders"), rs.getLong("revenue"), time(rs, "first_order_at"), time(rs, "last_order_at"),
                rs.getLong("cart_items"), time(rs, "cart_updated_at"),
                rs.getLong("wishlist_items"), time(rs, "wishlist_updated_at"),
                rs.getLong("clicks"), time(rs, "last_click_at"),
                rs.getLong("conversions"), rs.getLong("commission"), rs.getLong("sales"), time(rs, "last_conversion_at"),
                rs.getLong("reviews"),
                subscribed != null && rs.getBoolean("subscribed"));
    }

    // ------------------------------------------------------------------ customer timeline

    public List<TimelineEvent> timeline(Storefront sf, Long customerId) {
        MapSqlParameterSource p = new MapSqlParameterSource("sf", sf.name()).addValue("id", customerId);
        List<TimelineEvent> events = new ArrayList<>();
        events.addAll(jdbc.query("""
                select order_number, status, total_minor, placed_at from orders
                where storefront = :sf and customer_id = :id order by placed_at desc limit 50""", p,
                (rs, i) -> new TimelineEvent("ORDER", time(rs, "placed_at"), "Order " + rs.getString("order_number"),
                        rs.getString("status"), rs.getLong("total_minor"), null, rs.getString("order_number"))));
        events.addAll(jdbc.query("""
                select p.id, p.title, ci.quantity, ci.added_at from carts ca
                  join cart_items ci on ci.cart_id = ca.id join products p on p.id = ci.product_id
                where ca.storefront = :sf and ca.customer_id = :id""", p,
                (rs, i) -> new TimelineEvent("CART", time(rs, "added_at"), rs.getString("title"),
                        "In cart" + (rs.getInt("quantity") > 1 ? " (x" + rs.getInt("quantity") + ")" : ""), null, rs.getLong("id"), null)));
        events.addAll(jdbc.query("""
                select p.id, p.title, w.created_at from wishlist_items w join products p on p.id = w.product_id
                where w.storefront = :sf and w.customer_id = :id""", p,
                (rs, i) -> new TimelineEvent("WISHLIST", time(rs, "created_at"), rs.getString("title"), "Saved to wishlist",
                        null, rs.getLong("id"), null)));
        events.addAll(jdbc.query("""
                select p.id, p.title, ac.created_at from affiliate_clicks ac join products p on p.id = ac.product_id
                where ac.storefront = :sf and ac.customer_id = :id and ac.suspected_bot = false
                order by ac.created_at desc limit 50""", p,
                (rs, i) -> new TimelineEvent("CLICK", time(rs, "created_at"), rs.getString("title"), "Clicked through to the partner store",
                        null, rs.getLong("id"), null)));
        events.addAll(jdbc.query("""
                select p.id, p.title, cv.status, cv.commission_minor, cv.sale_amount_minor, cv.reported_at
                from affiliate_conversions cv join affiliate_clicks ac on ac.click_ref = cv.click_ref
                  left join products p on p.id = ac.product_id
                where cv.storefront = :sf and ac.customer_id = :id""", p,
                (rs, i) -> new TimelineEvent("CONVERSION", time(rs, "reported_at"),
                        rs.getString("title") == null ? "Partner sale" : rs.getString("title"),
                        "Partner sale " + rs.getString("status").toLowerCase(Locale.ROOT)
                                + (rs.getObject("commission_minor") == null ? "" : ", commission"),
                        rs.getObject("commission_minor", Long.class), rs.getObject("id", Long.class), null)));
        events.addAll(jdbc.query("""
                select p.id, p.title, r.rating, r.created_at from product_reviews r join products p on p.id = r.product_id
                where r.storefront = :sf and r.customer_id = :id""", p,
                (rs, i) -> new TimelineEvent("REVIEW", time(rs, "created_at"), rs.getString("title"),
                        "Reviewed: " + rs.getInt("rating") + " of 5 stars", null, rs.getLong("id"), null)));
        events.addAll(jdbc.query("""
                select cc.name, cr.sent_at from crm_campaign_recipients cr join crm_campaigns cc on cc.id = cr.campaign_id
                where cc.storefront = :sf and cr.customer_id = :id""", p,
                (rs, i) -> new TimelineEvent("EMAIL", time(rs, "sent_at"), rs.getString("name"), "Campaign email", null, null, null)));
        events.addAll(jdbc.query("""
                select kind, sent_at from crm_automation_log where storefront = :sf and customer_id = :id""", p,
                (rs, i) -> new TimelineEvent("EMAIL", time(rs, "sent_at"),
                        "CART_REMINDER".equals(rs.getString("kind")) ? "Cart reminder" : "Win-back email", "Automatic email", null, null, null)));
        events.removeIf(e -> e.at() == null);
        events.sort(Comparator.comparing(TimelineEvent::at).reversed());
        return events.size() > 150 ? events.subList(0, 150) : events;
    }

    // ------------------------------------------------------------------ tags

    public Map<Long, List<String>> tagsByCustomer(Storefront sf) {
        Map<Long, List<String>> out = new HashMap<>();
        jdbc.query("select customer_id, tag from crm_tags where storefront = :sf order by tag",
                new MapSqlParameterSource("sf", sf.name()),
                rs -> { out.computeIfAbsent(rs.getLong("customer_id"), k -> new ArrayList<>()).add(rs.getString("tag")); });
        return out;
    }

    public List<String> tags(Storefront sf, Long customerId) {
        return jdbc.queryForList("select tag from crm_tags where storefront = :sf and customer_id = :id order by tag",
                new MapSqlParameterSource("sf", sf.name()).addValue("id", customerId), String.class);
    }

    public void replaceTags(Storefront sf, Long customerId, Collection<String> tags) {
        MapSqlParameterSource p = new MapSqlParameterSource("sf", sf.name()).addValue("id", customerId);
        jdbc.update("delete from crm_tags where storefront = :sf and customer_id = :id", p);
        for (String tag : tags) {
            jdbc.update("insert into crm_tags (storefront, customer_id, tag, created_at) values (:sf, :id, :tag, :now)",
                    new MapSqlParameterSource(p.getValues()).addValue("tag", tag).addValue("now", utc(Instant.now())));
        }
    }

    public Map<String, Long> tagCounts(Storefront sf) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query("select tag, count(*) as n from crm_tags where storefront = :sf group by tag order by count(*) desc, tag",
                new MapSqlParameterSource("sf", sf.name()), rs -> { out.put(rs.getString("tag"), rs.getLong("n")); });
        return out;
    }

    // ------------------------------------------------------------------ campaigns

    /** Records a campaign recipient; false when they were already recorded (the campaign is being resumed). */
    public boolean addRecipient(Long campaignId, Long customerId, Instant at) {
        try {
            jdbc.update("insert into crm_campaign_recipients (campaign_id, customer_id, sent_at) values (:c, :id, :at)",
                    new MapSqlParameterSource("c", campaignId).addValue("id", customerId).addValue("at", utc(at)));
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    /** What recipients did in the attribution window after the campaign went out. */
    public CampaignResults campaignResults(Storefront sf, Long campaignId, Instant from, Instant to, String couponCode) {
        MapSqlParameterSource p = new MapSqlParameterSource("sf", sf.name()).addValue("c", campaignId)
                .addValue("from", utc(from)).addValue("to", utc(to))
                .addValue("purchase", PURCHASE_STATUSES).addValue("conversion", COUNTED_CONVERSIONS)
                .addValue("coupon", couponCode == null ? "" : couponCode);
        String recipients = "(select customer_id from crm_campaign_recipients where campaign_id = :c)";
        Map<String, Object> clicks = jdbc.queryForMap("select count(*) as n, count(distinct customer_id) as people from affiliate_clicks "
                + "where storefront = :sf and suspected_bot = false and created_at >= :from and created_at < :to and customer_id in " + recipients, p);
        Map<String, Object> conv = jdbc.queryForMap("select count(*) as n, sum(cv.commission_minor) as commission from affiliate_conversions cv "
                + "join affiliate_clicks ac on ac.click_ref = cv.click_ref where cv.storefront = :sf and cv.status in (:conversion) "
                + "and ac.created_at >= :from and ac.created_at < :to and ac.customer_id in " + recipients, p);
        Map<String, Object> orders = jdbc.queryForMap("select count(*) as n, sum(total_minor) as revenue, "
                + "sum(case when coupon_code = :coupon then 1 else 0 end) as coupon_orders from orders "
                + "where storefront = :sf and status in (:purchase) and placed_at >= :from and placed_at < :to and customer_id in " + recipients, p);
        return new CampaignResults(num(clicks.get("n")), num(clicks.get("people")), num(conv.get("n")), num(conv.get("commission")),
                num(orders.get("n")), num(orders.get("revenue")), num(orders.get("coupon_orders")));
    }

    // ------------------------------------------------------------------ automations

    public List<Line> cartLines(Storefront sf, Long customerId, int limit) {
        return jdbc.query("""
                select p.title, p.slug, ci.quantity from carts ca
                  join cart_items ci on ci.cart_id = ca.id join products p on p.id = ci.product_id
                where ca.storefront = :sf and ca.customer_id = :id order by ci.added_at desc limit :limit""",
                new MapSqlParameterSource("sf", sf.name()).addValue("id", customerId).addValue("limit", limit),
                (rs, i) -> new Line(rs.getString("title"), rs.getString("slug"), rs.getInt("quantity")));
    }

    public List<Line> latestProducts(Storefront sf, int limit) {
        return jdbc.query("""
                select title, slug from products where storefront = :sf and status = 'PUBLISHED'
                order by published_at desc limit :limit""",
                new MapSqlParameterSource("sf", sf.name()).addValue("limit", limit),
                (rs, i) -> new Line(rs.getString("title"), rs.getString("slug"), 1));
    }

    /** Claims an automated email so it is sent once; false if it was already sent (or claimed by another instance). */
    public boolean claimAutomation(Storefront sf, Long customerId, String kind, String ref, Instant at) {
        try {
            jdbc.update("insert into crm_automation_log (storefront, customer_id, kind, ref, sent_at) values (:sf, :id, :kind, :ref, :at)",
                    new MapSqlParameterSource("sf", sf.name()).addValue("id", customerId).addValue("kind", kind)
                            .addValue("ref", ref).addValue("at", utc(at)));
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    public boolean automationSentSince(Storefront sf, Long customerId, String kind, Instant since) {
        Long n = jdbc.queryForObject("select count(*) from crm_automation_log where storefront = :sf and customer_id = :id "
                        + "and kind = :kind and sent_at >= :since",
                new MapSqlParameterSource("sf", sf.name()).addValue("id", customerId).addValue("kind", kind).addValue("since", utc(since)),
                Long.class);
        return n != null && n > 0;
    }

    public Map<String, Long> automationCounts(Storefront sf, Instant since) {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("CART_REMINDER", 0L);
        out.put("WINBACK", 0L);
        jdbc.query("select kind, count(*) as n from crm_automation_log where storefront = :sf and sent_at >= :since group by kind",
                new MapSqlParameterSource("sf", sf.name()).addValue("since", utc(since)),
                rs -> { out.put(rs.getString("kind"), rs.getLong("n")); });
        return out;
    }

    // ------------------------------------------------------------------ store-wide automations

    /** Something a customer bought (partner sale or delivered order) that they have not reviewed yet. */
    public record ReviewCandidate(String ref, Long customerId, Long productId, String title, String slug) {
    }

    public List<ReviewCandidate> reviewCandidates(Storefront sf, Instant from, Instant to) {
        MapSqlParameterSource p = new MapSqlParameterSource("sf", sf.name()).addValue("from", utc(from)).addValue("to", utc(to));
        List<ReviewCandidate> out = new ArrayList<>(jdbc.query("""
                select cv.id as ref_id, ac.customer_id, p.id as product_id, p.title, p.slug
                from affiliate_conversions cv
                  join affiliate_clicks ac on ac.click_ref = cv.click_ref
                  join products p on p.id = ac.product_id
                where cv.storefront = :sf and cv.status in ('APPROVED', 'PAID') and ac.customer_id is not null
                  and cv.reported_at >= :from and cv.reported_at < :to and p.status = 'PUBLISHED'
                  and not exists (select 1 from product_reviews r where r.product_id = p.id and r.customer_id = ac.customer_id)""", p,
                (rs, i) -> new ReviewCandidate("conv:" + rs.getLong("ref_id"), rs.getLong("customer_id"), rs.getLong("product_id"),
                        rs.getString("title"), rs.getString("slug"))));
        out.addAll(jdbc.query("""
                select ol.id as ref_id, o.customer_id, p.id as product_id, p.title, p.slug
                from orders o join order_lines ol on ol.order_id = o.id join products p on p.id = ol.product_id
                where o.storefront = :sf and o.status in ('DELIVERED', 'COMPLETED')
                  and o.placed_at >= :from and o.placed_at < :to and p.status = 'PUBLISHED'
                  and not exists (select 1 from product_reviews r where r.product_id = p.id and r.customer_id = o.customer_id)""", p,
                (rs, i) -> new ReviewCandidate("line:" + rs.getLong("ref_id"), rs.getLong("customer_id"), rs.getLong("product_id"),
                        rs.getString("title"), rs.getString("slug"))));
        return out;
    }

    /** Customers who subscribed to this storefront's marketing email since {@code since}. */
    public List<Long> newSubscribers(Storefront sf, Instant since) {
        return jdbc.queryForList("select customer_id from marketing_consents where storefront = :sf and subscribed = true "
                + "and updated_at >= :since", new MapSqlParameterSource("sf", sf.name()).addValue("since", utc(since)), Long.class);
    }

    public long brokenLinkCount(Storefront sf) {
        return count("select count(*) from affiliate_links where storefront = :sf and active = true and health_status = 'BROKEN'",
                new MapSqlParameterSource("sf", sf.name()));
    }

    public List<String> masterAdminEmails(Storefront sf) {
        return jdbc.queryForList("select email from admin_users where storefront = :sf and role = 'MASTER_ADMIN' and status = 'ACTIVE'",
                new MapSqlParameterSource("sf", sf.name()), String.class);
    }

    /** The numbers in the daily summary, for activity since {@code since}. */
    public record DailyStats(long clicks, long partnerSales, long commissionMinor, long orders, long revenueMinor,
                             long newCustomers, long newSubscribers, long automatedEmails, long brokenLinks,
                             List<String> newlyBroken, long stalePrices, long tasksDue, long pendingReviews) {
    }

    public DailyStats dailyStats(Storefront sf, Instant since, Instant staleBefore, java.time.LocalDate today) {
        MapSqlParameterSource p = new MapSqlParameterSource("sf", sf.name()).addValue("since", utc(since))
                .addValue("stale", utc(staleBefore)).addValue("today", today)
                .addValue("purchase", PURCHASE_STATUSES).addValue("conversion", COUNTED_CONVERSIONS);
        long clicks = count("select count(*) from affiliate_clicks where storefront = :sf and suspected_bot = false and created_at >= :since", p);
        Map<String, Object> sales = jdbc.queryForMap("select count(*) as n, sum(commission_minor) as commission from affiliate_conversions "
                + "where storefront = :sf and status in (:conversion) and reported_at >= :since", p);
        Map<String, Object> orders = jdbc.queryForMap("select count(*) as n, sum(total_minor) as revenue from orders "
                + "where storefront = :sf and status in (:purchase) and placed_at >= :since", p);
        long customers = count("select count(*) from customers where signup_storefront = :sf and created_at >= :since", p);
        long subscribers = count("select count(*) from marketing_consents where storefront = :sf and subscribed = true and updated_at >= :since", p);
        long emails = count("select count(*) from crm_automation_log where storefront = :sf and customer_id > 0 and sent_at >= :since", p);
        long broken = count("select count(*) from affiliate_links where storefront = :sf and active = true and health_status = 'BROKEN'", p);
        List<String> newlyBroken = jdbc.query("""
                select coalesce(m.name, m.domain) as merchant, p.title from affiliate_links l
                  join affiliate_merchants m on m.id = l.merchant_id join products p on p.id = l.product_id
                where l.storefront = :sf and l.active = true and l.health_status = 'BROKEN' and l.health_checked_at >= :since
                order by p.title limit 10""", p, (rs, i) -> rs.getString("title") + " (" + rs.getString("merchant") + ")");
        long stale = count("""
                select count(*) from affiliate_links l join affiliate_merchants m on m.id = l.merchant_id
                where l.storefront = :sf and l.active = true and m.hide_prices = false and l.display_price_minor is not null
                  and (l.price_checked_at is null or l.price_checked_at < :stale)""", p);
        long tasks = count("select count(*) from crm_tasks where storefront = :sf and done_at is null and due_on <= :today", p);
        long reviews = count("select count(*) from product_reviews where storefront = :sf and status = 'PENDING'", p);
        return new DailyStats(clicks, num(sales.get("n")), num(sales.get("commission")), num(orders.get("n")), num(orders.get("revenue")),
                customers, subscribers, emails, broken, newlyBroken, stale, tasks, reviews);
    }

    private long count(String sql, MapSqlParameterSource p) {
        Long n = jdbc.queryForObject(sql, p, Long.class);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ helpers

    static LocalDateTime utc(Instant i) {
        return i == null ? null : LocalDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static Instant time(ResultSet rs, String column) throws SQLException {
        LocalDateTime t = rs.getObject(column, LocalDateTime.class);
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
