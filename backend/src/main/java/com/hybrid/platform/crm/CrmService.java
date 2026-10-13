package com.hybrid.platform.crm;

import com.hybrid.platform.commerce.settings.SettingsService;
import com.hybrid.platform.common.PageResponse;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.crm.CrmSegments.Segment;
import com.hybrid.platform.identity.AdminRole;
import com.hybrid.platform.identity.Customer;
import com.hybrid.platform.identity.CustomerRepository;
import com.hybrid.platform.security.AdminPrincipal;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Customer profiles, segments, notes, tags and follow-up tasks for one storefront's staff. */
@Service
public class CrmService {

    private static final Pattern TAG = Pattern.compile("^[a-z0-9][a-z0-9 _-]{0,29}$");
    private static final int MAX_TAGS = 15;

    public record CustomerRow(Long id, String name, String email, String phone, boolean active, boolean subscribed,
                              long purchases, long orders, long spendMinor, long revenueMinor, long commissionMinor,
                              long cartItems, long wishlistItems, long clicks, long reviews,
                              Instant signedUpAt, Instant lastActivityAt, Instant lastPurchaseAt, Instant cartUpdatedAt,
                              int intentScore, String temperature, List<String> segments, List<String> tags,
                              String nextActionCode, String nextAction) {
    }

    public record SegmentInfo(String key, String label, String description, long count, long subscribed) {
    }

    public record NoteView(Long id, String body, String adminEmail, Instant createdAt, boolean deletable) {
    }

    public record TaskView(Long id, Long customerId, String customerName, String customerEmail, String title,
                           LocalDate dueOn, boolean done, Instant doneAt, String createdByEmail, Instant createdAt) {
    }

    public record Overview(long customers, long newLast30Days, long subscribers, long hotLeads, long abandonedCarts,
                           long abandonedCartItems, long buyers, int repeatRatePercent, long openTasks,
                           List<SegmentInfo> segments, List<CustomerRow> hotList, List<TaskView> dueTasks,
                           boolean affiliateOnly, boolean smtpReady) {
    }

    public record CustomerDetail(CustomerRow customer, List<CrmQueries.TimelineEvent> timeline, List<NoteView> notes,
                                 List<TaskView> tasks, String whatsappNumber) {
    }

    public record TaskInput(Long customerId, String title, LocalDate dueOn, Boolean done) {
    }

    private final CrmQueries queries;
    private final CrmRepositories.NoteRepository notes;
    private final CrmRepositories.TaskRepository tasks;
    private final CustomerRepository customers;
    private final MarketingMailer mailer;
    private final SettingsService storeSettings;

    public CrmService(CrmQueries queries, CrmRepositories.NoteRepository notes, CrmRepositories.TaskRepository tasks,
                      CustomerRepository customers, MarketingMailer mailer, SettingsService storeSettings) {
        this.queries = queries;
        this.notes = notes;
        this.tasks = tasks;
        this.customers = customers;
        this.mailer = mailer;
        this.storeSettings = storeSettings;
    }

    // ------------------------------------------------------------------ overview & segments

    @Transactional(readOnly = true)
    public Overview overview(Storefront sf) {
        Instant now = Instant.now();
        List<CustomerSnapshot> all = queries.snapshots(sf);
        Map<Long, List<String>> tags = queries.tagsByCustomer(sf);
        Map<Segment, long[]> counts = new EnumMap<>(Segment.class);
        for (Segment s : Segment.values()) counts.put(s, new long[2]);
        List<CustomerRow> rows = new ArrayList<>();
        long abandonedItems = 0;
        long buyers = 0;
        long repeat = 0;
        long newCustomers = 0;
        for (CustomerSnapshot c : all) {
            CustomerRow row = row(c, now, tags);
            rows.add(row);
            for (String key : row.segments()) {
                long[] n = counts.get(Segment.valueOf(key));
                n[0]++;
                if (c.subscribed() && c.active()) n[1]++;
            }
            if (row.segments().contains(Segment.ABANDONED_CART.name())) abandonedItems += c.cartItems();
            if (c.purchases() > 0) buyers++;
            if (c.purchases() > 1) repeat++;
            if (c.signedUpHere() && c.signedUpAt() != null && c.signedUpAt().isAfter(now.minus(Duration.ofDays(30)))) newCustomers++;
        }
        List<SegmentInfo> segments = Arrays.stream(Segment.values())
                .map(s -> new SegmentInfo(s.name(), s.label(), s.description(), counts.get(s)[0], counts.get(s)[1])).toList();
        List<CustomerRow> hot = rows.stream().filter(r -> r.active() && r.intentScore() >= CrmSegments.HOT)
                .sorted(Comparator.comparingInt(CustomerRow::intentScore).reversed()
                        .thenComparing(CustomerRow::lastActivityAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(10).toList();
        List<TaskView> due = taskViews(tasks.open(sf, PageRequest.of(0, 8)));
        return new Overview(all.size(), newCustomers, counts.get(Segment.SUBSCRIBERS)[0], counts.get(Segment.HOT_LEADS)[0],
                counts.get(Segment.ABANDONED_CART)[0], abandonedItems, buyers, buyers == 0 ? 0 : (int) Math.round(100.0 * repeat / buyers),
                tasks.countByStorefrontAndDoneAtIsNull(sf), segments, hot, due, storeSettings.affiliateOnly(sf), mailer.smtpReady(sf));
    }

    public List<SegmentInfo> segmentDefinitions() {
        return Arrays.stream(Segment.values()).map(s -> new SegmentInfo(s.name(), s.label(), s.description(), 0, 0)).toList();
    }

    // ------------------------------------------------------------------ customers

    @Transactional(readOnly = true)
    public PageResponse<CustomerRow> list(Storefront sf, String segment, String q, String tag, String sort, int page, int size) {
        Instant now = Instant.now();
        Segment seg = parseSegment(segment, Segment.ALL);
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        String tagFilter = tag == null || tag.isBlank() ? null : tag.trim().toLowerCase(Locale.ROOT);
        Map<Long, List<String>> tags = queries.tagsByCustomer(sf);
        List<CustomerRow> rows = queries.snapshots(sf).stream()
                .map(c -> row(c, now, tags))
                .filter(r -> r.segments().contains(seg.name()))
                .filter(r -> tagFilter == null || r.tags().contains(tagFilter))
                .filter(r -> needle.isEmpty() || contains(r.email(), needle) || contains(r.name(), needle) || contains(r.phone(), needle))
                .sorted(sorter(sort))
                .toList();
        int s = Math.min(Math.max(size, 1), 100);
        int p = Math.max(page, 0);
        int from = Math.min(p * s, rows.size());
        List<CustomerRow> items = rows.subList(from, Math.min(from + s, rows.size()));
        return new PageResponse<>(items, p, s, rows.size(), (rows.size() + s - 1) / s);
    }

    @Transactional(readOnly = true)
    public CustomerDetail detail(AdminPrincipal admin, Long customerId) {
        Storefront sf = admin.storefront();
        CustomerSnapshot c = require(sf, customerId);
        Map<Long, List<String>> tags = Map.of(customerId, queries.tags(sf, customerId));
        List<NoteView> noteViews = notes.findByStorefrontAndCustomerIdOrderByCreatedAtDesc(sf, customerId).stream()
                .map(n -> new NoteView(n.getId(), n.getBody(), n.getAdminEmail(), n.getCreatedAt(), canDelete(admin, n))).toList();
        List<CrmQueries.TimelineEvent> timeline = new ArrayList<>(queries.timeline(sf, customerId));
        if (c.signedUpHere() && c.signedUpAt() != null) {
            timeline.add(new CrmQueries.TimelineEvent("SIGNUP", c.signedUpAt(), "Created an account", "Signed up on this store",
                    null, null, null));
            timeline.sort(Comparator.comparing(CrmQueries.TimelineEvent::at).reversed());
        }
        return new CustomerDetail(row(c, Instant.now(), tags), timeline, noteViews,
                taskViews(tasks.findByStorefrontAndCustomerIdOrderByCreatedAtDesc(sf, customerId)), whatsapp(c.phone()));
    }

    /** Recipients for a campaign: active, subscribed customers in the segment. */
    @Transactional(readOnly = true)
    public List<CustomerSnapshot> audience(Storefront sf, Segment segment) {
        Instant now = Instant.now();
        return queries.snapshots(sf).stream()
                .filter(c -> c.active() && c.subscribed() && c.email() != null)
                .filter(c -> CrmSegments.segments(c, now).contains(segment))
                .sorted(Comparator.comparing(CustomerSnapshot::id))
                .toList();
    }

    @Transactional(readOnly = true)
    public long segmentSize(Storefront sf, Segment segment) {
        Instant now = Instant.now();
        return queries.snapshots(sf).stream().filter(c -> CrmSegments.segments(c, now).contains(segment)).count();
    }

    // ------------------------------------------------------------------ notes

    @Transactional
    public NoteView addNote(AdminPrincipal admin, Long customerId, String body) {
        require(admin.storefront(), customerId);
        String text = body == null ? "" : body.trim();
        if (text.isEmpty() || text.length() > 2000) {
            throw ApiException.badRequest("NOTE_INVALID", "Write a note of 1 to 2000 characters");
        }
        CrmNote n = notes.save(new CrmNote(admin.storefront(), customerId, admin.id(), admin.email(), text));
        return new NoteView(n.getId(), n.getBody(), n.getAdminEmail(), n.getCreatedAt(), true);
    }

    @Transactional
    public void deleteNote(AdminPrincipal admin, Long noteId) {
        CrmNote n = notes.findByIdAndStorefront(noteId, admin.storefront())
                .orElseThrow(() -> ApiException.notFound("NOTE_NOT_FOUND", "Note not found"));
        if (!canDelete(admin, n)) {
            throw ApiException.forbidden("NOT_NOTE_AUTHOR", "Only the author or a master admin can delete this note");
        }
        notes.delete(n);
    }

    // ------------------------------------------------------------------ tags

    @Transactional
    public List<String> setTags(AdminPrincipal admin, Long customerId, List<String> raw) {
        require(admin.storefront(), customerId);
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        for (String t : raw == null ? List.<String>of() : raw) {
            if (t == null) continue;
            String v = t.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (v.isEmpty()) continue;
            if (!TAG.matcher(v).matches()) {
                throw ApiException.badRequest("TAG_INVALID", "Tags use letters, numbers, spaces, - and _ (up to 30 characters): " + t);
            }
            clean.add(v);
        }
        if (clean.size() > MAX_TAGS) {
            throw ApiException.badRequest("TOO_MANY_TAGS", "A customer can have at most " + MAX_TAGS + " tags");
        }
        queries.replaceTags(admin.storefront(), customerId, clean);
        return queries.tags(admin.storefront(), customerId);
    }

    public Map<String, Long> tagCounts(Storefront sf) {
        return queries.tagCounts(sf);
    }

    // ------------------------------------------------------------------ tasks

    @Transactional(readOnly = true)
    public List<TaskView> listTasks(Storefront sf, boolean done) {
        return taskViews(done ? tasks.recentlyDone(sf, PageRequest.of(0, 50)) : tasks.open(sf, PageRequest.of(0, 200)));
    }

    @Transactional
    public TaskView createTask(AdminPrincipal admin, TaskInput in) {
        if (in == null || in.customerId() == null) {
            throw ApiException.badRequest("TASK_CUSTOMER_REQUIRED", "Choose the customer this task is about");
        }
        require(admin.storefront(), in.customerId());
        CrmTask t = tasks.save(new CrmTask(admin.storefront(), in.customerId(), title(in.title()), in.dueOn(), admin.id(), admin.email()));
        return taskViews(List.of(t)).get(0);
    }

    @Transactional
    public TaskView updateTask(AdminPrincipal admin, Long id, TaskInput in) {
        CrmTask t = tasks.findByIdAndStorefront(id, admin.storefront())
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "Task not found"));
        if (in != null && in.done() != null) t.setDone(in.done());
        return taskViews(List.of(tasks.save(t))).get(0);
    }

    @Transactional
    public void deleteTask(AdminPrincipal admin, Long id) {
        CrmTask t = tasks.findByIdAndStorefront(id, admin.storefront())
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "Task not found"));
        tasks.delete(t);
    }

    // ------------------------------------------------------------------ helpers

    static Segment parseSegment(String value, Segment fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Segment.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("SEGMENT_INVALID", "Unknown segment: " + value);
        }
    }

    private CustomerSnapshot require(Storefront sf, Long customerId) {
        return queries.snapshot(sf, customerId)
                .orElseThrow(() -> ApiException.notFound("CUSTOMER_NOT_FOUND", "This customer has not used this store"));
    }

    private static CustomerRow row(CustomerSnapshot c, Instant now, Map<Long, List<String>> tags) {
        Set<Segment> segs = CrmSegments.segments(c, now);
        int score = CrmSegments.intentScore(c, now);
        CrmSegments.NextAction next = CrmSegments.nextAction(c, segs, now);
        return new CustomerRow(c.id(), c.fullName(), c.email(), c.phone(), c.active(), c.subscribed(),
                c.purchases(), c.orders(), c.spendMinor(), c.revenueMinor(), c.commissionMinor(),
                c.cartItems(), c.wishlistItems(), c.clicks(), c.reviews(),
                c.signedUpAt(), c.lastActivityAt(), c.lastPurchaseAt(), c.cartUpdatedAt(),
                score, CrmSegments.temperature(score), segs.stream().map(Enum::name).toList(),
                tags.getOrDefault(c.id(), List.of()), next.code(), next.text());
    }

    private static Comparator<CustomerRow> sorter(String sort) {
        Comparator<Instant> newestFirst = Comparator.nullsLast(Comparator.reverseOrder());
        Comparator<CustomerRow> byActivity = Comparator.comparing(CustomerRow::lastActivityAt, newestFirst);
        return switch (sort == null ? "" : sort) {
            case "intent" -> Comparator.comparingInt(CustomerRow::intentScore).reversed().thenComparing(byActivity);
            case "value" -> Comparator.comparingLong(CustomerRow::spendMinor).reversed().thenComparing(byActivity);
            case "purchases" -> Comparator.comparingLong(CustomerRow::purchases).reversed().thenComparing(byActivity);
            case "newest" -> Comparator.comparing(CustomerRow::signedUpAt, newestFirst);
            case "name" -> Comparator.comparing(r -> r.name() == null ? "" : r.name().toLowerCase(Locale.ROOT));
            default -> byActivity;
        };
    }

    private List<TaskView> taskViews(List<CrmTask> list) {
        Map<Long, Customer> byId = customers.findAllById(list.stream().map(CrmTask::getCustomerId).distinct().toList())
                .stream().collect(Collectors.toMap(Customer::getId, Function.identity()));
        return list.stream().map(t -> {
            Customer c = byId.get(t.getCustomerId());
            return new TaskView(t.getId(), t.getCustomerId(), c == null ? null : c.getFullName(), c == null ? null : c.getEmail(),
                    t.getTitle(), t.getDueOn(), t.getDoneAt() != null, t.getDoneAt(), t.getCreatedByEmail(), t.getCreatedAt());
        }).toList();
    }

    private static boolean canDelete(AdminPrincipal admin, CrmNote n) {
        return admin.role() == AdminRole.MASTER_ADMIN || Objects.equals(admin.id(), n.getAdminId());
    }

    private static String title(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty() || t.length() > 200) {
            throw ApiException.badRequest("TASK_TITLE_INVALID", "Describe the task in 1 to 200 characters");
        }
        return t;
    }

    /** Digits for a wa.me click-to-chat link; 10-digit numbers are assumed to be Indian mobiles (+91). */
    static String whatsapp(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() == 10) return "91" + digits;
        if (digits.length() == 11 && digits.startsWith("0")) return "91" + digits.substring(1);
        return digits.length() >= 11 && digits.length() <= 15 ? digits : null;
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }
}
