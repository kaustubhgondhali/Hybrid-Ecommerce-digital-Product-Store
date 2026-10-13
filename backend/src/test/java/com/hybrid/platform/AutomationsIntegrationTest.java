package com.hybrid.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hybrid.platform.affiliate.LinkHealthService;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.crm.CrmAutomationService;
import com.hybrid.platform.identity.AdminRole;
import com.hybrid.platform.identity.AdminUser;
import com.hybrid.platform.identity.AdminUserRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Free automations: dead-link check, Telegram posts, welcome / weekly / review emails, daily summary, wishlist alerts. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AutomationsIntegrationTest {

    private static final String PASSWORD = "Auto-Test-Passw0rd-1";
    private static final String ADMIN = "/api/digital/admin";
    private static final String TOKEN = "123456789:AAH_test-token-0123456789abcdefghij";

    /** A fake partner website and a fake Telegram API on one local port. */
    private static final HttpServer SERVER;
    private static final AtomicInteger GONE_STATUS = new AtomicInteger(404);
    private static final Queue<String> TELEGRAM = new ConcurrentLinkedQueue<>();

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        SERVER.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            int code;
            byte[] body = new byte[0];
            if (path.startsWith("/bot")) {
                String request = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                TELEGRAM.add(path + " " + request);
                code = 200;
                body = (request.contains("@missing_chat") ? "{\"ok\":false,\"description\":\"Bad Request: chat not found\"}"
                        : "{\"ok\":true,\"result\":{}}").getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
            } else if (path.equals("/live")) {
                code = 200;
            } else if (path.equals("/gone")) {
                code = GONE_STATUS.get();
            } else if (path.equals("/blocked")) {
                code = 403;
            } else if (path.equals("/moved")) {
                code = 302;
                ex.getResponseHeaders().add("Location", "/live");
            } else {
                code = 404;
            }
            ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
            if (body.length > 0) ex.getResponseBody().write(body);
            ex.close();
        });
        SERVER.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.telegram.api-base", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        r.add("app.affiliate.link-check-allow-private", () -> "true");
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    AdminUserRepository admins;
    @Autowired
    PasswordEncoder passwords;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    LinkHealthService linkHealth;
    @Autowired
    CrmAutomationService automations;

    @BeforeEach
    void staff() {
        if (admins.findByStorefrontAndEmailIgnoreCase(Storefront.DIGITAL, "auto-master@test.local").isEmpty()) {
            admins.save(new AdminUser(Storefront.DIGITAL, "auto-master@test.local", passwords.encode(PASSWORD), "Auto",
                    AdminRole.MASTER_ADMIN, false));
        }
    }

    @Test
    void deadLinksAreHiddenAfterTwoChecksAndComeBack() throws Exception {
        MockHttpSession admin = login();
        long gone = createPublished(admin, "Auto Gone Course", "https://www.udemy.com/course/auto-gone/", 50000L);
        long blocked = createPublished(admin, "Auto Blocked Course", "https://www.udemy.com/course/auto-blocked/", 50000L);
        long moved = createPublished(admin, "Auto Moved Course", "https://www.udemy.com/course/auto-moved/", 50000L);
        String base = "http://127.0.0.1:" + SERVER.getAddress().getPort();
        jdbc.update("update affiliate_links set destination_url = ? where product_id = ?", base + "/gone", gone);
        jdbc.update("update affiliate_links set destination_url = ? where product_id = ?", base + "/blocked", blocked);
        jdbc.update("update affiliate_links set destination_url = ? where product_id = ?", base + "/moved", moved);
        // only check this test's links (others in the shared test database point at real websites)
        List<Long> paused = jdbc.queryForList("select id from affiliate_links where storefront = 'DIGITAL' and active = true "
                + "and destination_url not like ?", Long.class, base + "%");
        paused.forEach(id -> jdbc.update("update affiliate_links set active = false where id = ?", id));
        try {
            LinkHealthService.CheckResult first = linkHealth.check(Storefront.DIGITAL, 50, Duration.ZERO);
            assertThat(first.checked()).isEqualTo(3);
            assertThat(first.broken()).as("one 'not found' is not enough to hide a deal").isZero();
            assertThat(offerCount(admin, gone)).isEqualTo(1);

            LinkHealthService.CheckResult second = linkHealth.check(Storefront.DIGITAL, 50, Duration.ZERO);
            assertThat(second.broken()).isEqualTo(1);
            assertThat(second.newlyBroken()).hasSize(1);
            assertThat(offerCount(admin, gone)).as("dead deal hidden from shoppers").isZero();
            assertThat(offerCount(admin, blocked)).as("robot-blocking store is never hidden").isEqualTo(1);
            assertThat(offerCount(admin, moved)).isEqualTo(1);
            assertThat(health(moved)).isEqualTo("OK");
            assertThat(health(blocked)).isEqualTo("UNKNOWN");

            GONE_STATUS.set(200);
            linkHealth.check(Storefront.DIGITAL, 50, Duration.ZERO);
            assertThat(health(gone)).isEqualTo("OK");
            assertThat(offerCount(admin, gone)).as("back as soon as the page works again").isEqualTo(1);
        } finally {
            GONE_STATUS.set(404);
            paused.forEach(id -> jdbc.update("update affiliate_links set active = true where id = ?", id));
            jdbc.update("update affiliate_links set active = false where destination_url like ?", base + "%");
        }
    }

    @Test
    void telegramPostsNewDealsAndPriceDrops() throws Exception {
        MockHttpSession admin = login();
        call(admin, put(ADMIN + "/crm/automations"), Map.of("telegramToken", "not-a-token")).andExpect(status().isBadRequest());
        call(admin, put(ADMIN + "/crm/automations"), Map.of("telegramEnabled", true))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TELEGRAM_INCOMPLETE"));
        call(admin, put(ADMIN + "/crm/automations"), Map.of("telegramToken", TOKEN, "telegramChatId", "@missing_chat",
                "telegramEnabled", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.telegramTokenSet").value(true))
                .andExpect(jsonPath("$.telegramToken").doesNotExist());
        call(admin, post(ADMIN + "/crm/automations/telegram-test"), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("chat not found")));
        call(admin, put(ADMIN + "/crm/automations"), Map.of("telegramChatId", "@elaatech_deals")).andExpect(status().isOk());
        TELEGRAM.clear();
        call(admin, post(ADMIN + "/crm/automations/telegram-test"), null).andExpect(status().isNoContent());
        assertThat(TELEGRAM.poll()).startsWith("/bot" + TOKEN + "/sendMessage").contains("@elaatech_deals").contains("Test message");
        try {
            long id = createPublished(admin, "Auto Telegram Course", "https://www.coursera.org/learn/auto-tg", 120000L);
            waitFor(() -> TELEGRAM.stream().anyMatch(m -> m.contains("New deal: Auto Telegram Course")));
            String slug = body(call(admin, get(ADMIN + "/products/" + id), null)).get("slug").asText();
            assertThat(TELEGRAM.stream().filter(m -> m.contains("New deal: Auto Telegram Course")).findFirst().orElseThrow())
                    .contains("/digital/p/" + slug).contains("1,200");
            call(admin, put(ADMIN + "/products/" + id + "/affiliate-link"),
                    link("https://www.coursera.org/learn/auto-tg", 99000L)).andExpect(status().isOk());
            waitFor(() -> TELEGRAM.stream().anyMatch(m -> m.contains("Price drop: Auto Telegram Course")));
            Thread.sleep(300);
            assertThat(TELEGRAM.stream().filter(m -> m.contains("New deal: Auto Telegram Course")).count()).as("posted once").isEqualTo(1);
        } finally {
            call(admin, put(ADMIN + "/crm/automations"), Map.of("telegramEnabled", false)).andExpect(status().isOk());
        }
    }

    @Test
    void welcomeWeeklyReviewAndSummaryEmailsGoOutOnce() throws Exception {
        MockHttpSession admin = login();
        long course = createPublished(admin, "Auto Email Course", "https://www.udemy.com/course/auto-email/", 80000L);
        MockHttpSession saver = new MockHttpSession();
        call(saver, post("/api/auth/register"), Map.of("email", "auto-subscriber@test.local", "password", "Subscriber-Passw0rd1",
                "fullName", "Meera Auto", "storefront", "digital", "marketingOptIn", true)).andExpect(status().isCreated());
        Long customer = jdbc.queryForObject("select id from customers where email = ?", Long.class, "auto-subscriber@test.local");
        Long linkId = jdbc.queryForObject("select id from affiliate_links where product_id = ?", Long.class, course);

        ZonedDateTime monday = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
                .withHour(11).withMinute(0);
        Instant now = monday.toInstant();
        // she bought the course through our link 8 days before "now": a review request is due
        jdbc.update("insert into affiliate_clicks (click_ref, link_id, storefront, product_id, customer_id, suspected_bot, created_at) "
                + "values ('auto-click-1', ?, 'DIGITAL', ?, ?, false, ?)", linkId, course, customer, utc(now.minus(Duration.ofDays(9))));
        jdbc.update("insert into affiliate_conversions (storefront, click_ref, external_txn_id, sale_amount_minor, commission_minor, status, "
                + "source, reported_at, updated_at) values ('DIGITAL', 'auto-click-1', 'AUTO-TXN-1', 80000, 8000, 'APPROVED', 'MANUAL', ?, ?)",
                utc(now.minus(Duration.ofDays(8))), utc(now.minus(Duration.ofDays(8))));

        call(admin, put(ADMIN + "/settings/smtp"), Map.of("enabled", true, "host", "localhost", "port", 1, "security", "NONE",
                "fromEmail", "digital@test.local")).andExpect(status().isOk());
        try {
            call(admin, put(ADMIN + "/crm/automations"), Map.of("welcomeEnabled", true, "weeklyDigestEnabled", true, "weeklyDigestDay", 1,
                    "reviewRequestEnabled", true, "reviewRequestDays", 7, "dailySummaryEnabled", true, "dailySummaryHour", 9))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.weeklyDigestEnabled").value(true));

            automations.runDue(Storefront.DIGITAL, now);
            assertThat(mails("CRM_WELCOME", "auto-subscriber@test.local")).isEqualTo(1);
            assertThat(mails("CRM_REVIEW_REQUEST", "auto-subscriber@test.local")).isEqualTo(1);
            assertThat(mails("DAILY_SUMMARY", "auto-master@test.local")).isEqualTo(1);
            assertThat(mails("CRM_WEEKLY_DIGEST", "auto-subscriber@test.local")).as("one email per customer per run").isZero();

            automations.runDue(Storefront.DIGITAL, now.plus(Duration.ofMinutes(60)));
            assertThat(mails("CRM_WEEKLY_DIGEST", "auto-subscriber@test.local")).isEqualTo(1);
            Map<String, Object> digest = jdbc.queryForMap("select subject from notification_log where kind = 'CRM_WEEKLY_DIGEST' and recipient = ?",
                    "auto-subscriber@test.local");
            assertThat((String) digest.get("subject")).startsWith("This week's top deals at ");

            automations.runDue(Storefront.DIGITAL, now.plus(Duration.ofMinutes(120)));
            assertThat(mails("CRM_WELCOME", "auto-subscriber@test.local")).isEqualTo(1);
            assertThat(mails("CRM_REVIEW_REQUEST", "auto-subscriber@test.local")).isEqualTo(1);
            assertThat(mails("CRM_WEEKLY_DIGEST", "auto-subscriber@test.local")).isEqualTo(1);
            assertThat(mails("DAILY_SUMMARY", "auto-master@test.local")).isEqualTo(1);
        } finally {
            call(admin, put(ADMIN + "/crm/automations"), Map.of("welcomeEnabled", false, "weeklyDigestEnabled", false,
                    "reviewRequestEnabled", false, "dailySummaryEnabled", false)).andExpect(status().isOk());
            call(admin, put(ADMIN + "/settings/smtp"), Map.of("enabled", false)).andExpect(status().isOk());
        }
    }

    @Test
    void wishlistSaversGetPriceDropAlerts() throws Exception {
        MockHttpSession admin = login();
        long id = createPublished(admin, "Auto Wishlist Templates", "https://www.etsy.com/listing/auto-wish", 60000L);
        String slug = body(call(admin, get(ADMIN + "/products/" + id), null)).get("slug").asText();
        MockHttpSession shopper = new MockHttpSession();
        call(shopper, post("/api/auth/register"), Map.of("email", "auto-wisher@test.local", "password", "Wisher-Passw0rd1",
                "fullName", "Wish Er", "storefront", "digital")).andExpect(status().isCreated());
        call(shopper, put("/api/digital/wishlist/" + slug), null).andExpect(status().isOk());
        call(admin, put(ADMIN + "/products/" + id + "/affiliate-link"), link("https://www.etsy.com/listing/auto-wish", 50000L))
                .andExpect(status().isOk());
        waitFor(() -> mails("PRICE_DROP", "auto-wisher@test.local") == 1);
    }

    // ------------------------------------------------------------------ helpers

    private long createPublished(MockHttpSession admin, String title, String url, Long price) throws Exception {
        JsonNode p = body(call(admin, post(ADMIN + "/products"), Map.of("productType", "DIGITAL", "sellingMethod", "AFFILIATE",
                "title", title, "deliveryKind", "DOWNLOAD")).andExpect(status().isCreated()));
        long id = p.get("id").asLong();
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        mvc.perform(multipart(ADMIN + "/products/" + id + "/images").file(new MockMultipartFile("file", "p.png", "image/png", out.toByteArray()))
                .session(admin).with(csrf())).andExpect(status().isOk());
        Map<String, Object> l = link(url, price);
        l.put("confirmNewDomain", true);
        call(admin, put(ADMIN + "/products/" + id + "/affiliate-link"), l).andExpect(status().isOk());
        call(admin, post(ADMIN + "/products/" + id + "/status"), Map.of("status", "PUBLISHED")).andExpect(status().isOk());
        return id;
    }

    private static Map<String, Object> link(String url, Long price) {
        Map<String, Object> m = new HashMap<>();
        m.put("url", url);
        m.put("displayPriceMinor", price);
        return m;
    }

    private int offerCount(MockHttpSession admin, long productId) throws Exception {
        String slug = body(call(admin, get(ADMIN + "/products/" + productId), null)).get("slug").asText();
        JsonNode d = body(call(new MockHttpSession(), get("/api/digital/catalog/products/" + slug), null).andExpect(status().isOk()));
        return d.get("offers").size();
    }

    private String health(long productId) {
        return jdbc.queryForObject("select health_status from affiliate_links where product_id = ?", String.class, productId);
    }

    private int mails(String kind, String recipient) {
        Integer n = jdbc.queryForObject("select count(*) from notification_log where kind = ? and recipient = ?", Integer.class, kind, recipient);
        return n == null ? 0 : n;
    }

    private static LocalDateTime utc(Instant i) {
        return LocalDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 80 && !condition.getAsBoolean(); i++) Thread.sleep(100);
        assertThat(condition.getAsBoolean()).as("condition within 8 seconds").isTrue();
    }

    private MockHttpSession login() throws Exception {
        MockHttpSession s = new MockHttpSession();
        call(s, post(ADMIN + "/auth/login"), Map.of("email", "auto-master@test.local", "password", PASSWORD)).andExpect(status().isOk());
        return s;
    }

    private ResultActions call(MockHttpSession session, MockHttpServletRequestBuilder req, Object body) throws Exception {
        req.session(session).with(csrf()).accept(MediaType.APPLICATION_JSON);
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        }
        return mvc.perform(req);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsByteArray());
    }
}
