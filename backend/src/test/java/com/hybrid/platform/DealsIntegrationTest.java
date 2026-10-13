package com.hybrid.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.identity.AdminRole;
import com.hybrid.platform.identity.AdminUser;
import com.hybrid.platform.identity.AdminUserRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Deal cards and pages: discounts, hidden Amazon prices, price history, several stores per product, deal content. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DealsIntegrationTest {

    private static final String PASSWORD = "Deals-Test-Passw0rd-1";
    private static final String UA = "Mozilla/5.0 (integration test)";
    private static final String ADMIN = "/api/digital/admin";

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

    @BeforeEach
    void staff() {
        if (admins.findByStorefrontAndEmailIgnoreCase(Storefront.DIGITAL, "deals-master@test.local").isEmpty()) {
            admins.save(new AdminUser(Storefront.DIGITAL, "deals-master@test.local", passwords.encode(PASSWORD), "Deals",
                    AdminRole.MASTER_ADMIN, false));
        }
    }

    @Test
    void dealSignalsComparisonAndContent() throws Exception {
        MockHttpSession admin = login();

        // ---- a course sold by two stores, and an app sold only on Amazon (whose prices must never be shown)
        long course = createPublished(admin, "Deals Test Python Course", "https://www.udemy.com/course/deals-python/", 200000L, 250000L);
        long app = createPublished(admin, "Deals Test Photo App", "https://www.amazon.in/dp/DEALS1", 150000L, 300000L);
        String courseSlug = slug(admin, course);
        String appSlug = slug(admin, app);

        JsonNode list = search("deals test");
        JsonNode courseCard = card(list, course);
        assertThat(courseCard.get("priceMinor").asLong()).isEqualTo(200000);
        assertThat(courseCard.get("compareAtMinor").asLong()).isEqualTo(250000);
        assertThat(courseCard.get("discountPercent").asInt()).isEqualTo(20);
        assertThat(courseCard.get("merchantName").asText()).isEqualTo("udemy.com");
        assertThat(courseCard.get("checkPrice").asBoolean()).isFalse();
        JsonNode appCard = card(list, app);
        assertThat(appCard.hasNonNull("priceMinor")).as("Amazon price hidden").isFalse();
        assertThat(appCard.hasNonNull("compareAtMinor")).isFalse();
        assertThat(appCard.get("checkPrice").asBoolean()).isTrue();

        call(new MockHttpSession(), get("/api/digital/catalog/products/" + appSlug), null)
                .andExpect(jsonPath("$.affiliate.priceHidden").value(true))
                .andExpect(jsonPath("$.affiliate.displayPriceMinor").doesNotExist());
        String appPage = mvc.perform(get("/digital/p/" + appSlug)).andReturn().getResponse().getContentAsString();
        assertThat(appPage).contains("Check price on amazon.in").doesNotContain("1,500.00").doesNotContain("\"price\":\"1500.00\"");

        // ---- a 10% cut is a price drop and today's price becomes the 30-day low; Amazon cuts never notify anyone
        MockHttpSession saver = new MockHttpSession();
        call(saver, post("/api/auth/register"), Map.of("email", "deals-saver@test.local", "password", "Saver-Passw0rd1",
                "fullName", "Deal Saver", "storefront", "digital")).andExpect(status().isCreated());
        for (long id : new long[]{course, app}) {
            long variant = body(call(admin, get(ADMIN + "/products/" + id), null)).get("variants").get(0).get("id").asLong();
            call(saver, post("/api/digital/cart/items"), Map.of("variantId", variant)).andExpect(status().isOk());
        }
        setLink(admin, course, "https://www.udemy.com/course/deals-python/", 180000L, 250000L);
        setLink(admin, app, "https://www.amazon.in/dp/DEALS1", 100000L, 300000L);
        waitFor(() -> priceDropMails("Deals Test Python Course") == 1);
        Thread.sleep(300);
        JsonNode cut = card(search("deals test"), course);
        assertThat(cut.get("priceMinor").asLong()).isEqualTo(180000);
        assertThat(cut.get("dropPercent").asInt()).isEqualTo(10);
        assertThat(cut.get("lowest30d").asBoolean()).isTrue();
        call(new MockHttpSession(), get("/api/digital/catalog/products/" + courseSlug), null)
                .andExpect(jsonPath("$.priceStats.lowest30d").value(true))
                .andExpect(jsonPath("$.priceStats.low30Minor").value(180000))
                .andExpect(jsonPath("$.priceStats.high30Minor").value(200000));
        assertThat(priceDropMails("Deals Test Photo App")).as("Amazon prices must never be revealed").isZero();

        // ---- a second store: the cheaper one becomes the best offer, both are listed for comparison
        JsonNode second = body(call(admin, post(ADMIN + "/products/" + course + "/affiliate-links"),
                link("https://www.coursera.org/learn/deals-python", 175000L, null, true)).andExpect(status().isOk()));
        long secondId = second.get("id").asLong();
        call(new MockHttpSession(), get("/api/digital/catalog/products/" + courseSlug), null)
                .andExpect(jsonPath("$.offers.length()").value(2))
                .andExpect(jsonPath("$.offers[0].merchantName").value("coursera.org"))
                .andExpect(jsonPath("$.offers[0].displayPriceMinor").value(175000))
                .andExpect(jsonPath("$.offers[1].merchantName").value("udemy.com"))
                .andExpect(jsonPath("$.affiliate.merchantName").value("coursera.org"));
        assertThat(card(search("deals test"), course)
                .get("offerCount").asInt()).isEqualTo(2);
        String coursePage = mvc.perform(get("/digital/p/" + courseSlug)).andReturn().getResponse().getContentAsString();
        assertThat(coursePage).contains("\"@type\":\"AggregateOffer\"").contains("\"offerCount\":2");

        // adding the same store again updates its link instead of duplicating it
        call(admin, post(ADMIN + "/products/" + course + "/affiliate-links"),
                link("https://www.coursera.org/learn/deals-python?ref=2", 170000L, null, false)).andExpect(status().isOk());
        call(admin, get(ADMIN + "/products/" + course + "/affiliate-links"), null).andExpect(jsonPath("$.length()").value(2));
        // a link cannot be moved onto a store the product already has
        call(admin, put(ADMIN + "/products/" + course + "/affiliate-links/" + secondId),
                link("https://www.udemy.com/course/other/", 1L, null, false))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MERCHANT_ALREADY_LINKED"));
        // pausing a store hides it from shoppers
        call(admin, patch(ADMIN + "/products/" + course + "/affiliate-links/" + secondId), Map.of("active", false))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        call(new MockHttpSession(), get("/api/digital/catalog/products/" + courseSlug), null)
                .andExpect(jsonPath("$.offers.length()").value(1))
                .andExpect(jsonPath("$.affiliate.merchantName").value("udemy.com"));

        // ---- deal content
        Map<String, Object> content = new HashMap<>(Map.of("highlights", "- 40 hours of video\n\n* Certificate of completion\n",
                "pros", "Beginner friendly", "cons", "English only", "verdict", "The best value Python course right now.",
                "expertRating", 45));
        call(admin, put(ADMIN + "/products/" + course), content).andExpect(status().isOk())
                .andExpect(jsonPath("$.highlights").value("40 hours of video\nCertificate of completion"))
                .andExpect(jsonPath("$.expertRating").value(45));
        call(admin, put(ADMIN + "/products/" + course), Map.of("expertRating", 47)).andExpect(status().isBadRequest());
        call(admin, put(ADMIN + "/products/" + course), Map.of("pros", "a\nb\nc\nd\ne\nf\ng\nh\ni")).andExpect(status().isBadRequest());
        call(new MockHttpSession(), get("/api/digital/catalog/products/" + courseSlug), null)
                .andExpect(jsonPath("$.highlights.length()").value(2))
                .andExpect(jsonPath("$.pros[0]").value("Beginner friendly"))
                .andExpect(jsonPath("$.verdict").value("The best value Python course right now."))
                .andExpect(jsonPath("$.expertRating").value(45))
                .andExpect(jsonPath("$.similar.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        String withContent = mvc.perform(get("/digital/p/" + courseSlug)).andReturn().getResponse().getContentAsString();
        assertThat(withContent).contains("<h2>Highlights</h2>").contains("Certificate of completion").contains("Our verdict");

        // ---- home sections: discounts, price drops and (after real clicks) trending
        String code = body(call(admin, get(ADMIN + "/products/" + course + "/affiliate-links"), null)).get(0).get("code").asText();
        mvc.perform(get("/go/" + code).header("User-Agent", UA)).andExpect(status().isFound());
        JsonNode home = body(call(new MockHttpSession(), get("/api/digital/catalog/home"), null).andExpect(status().isOk()));
        assertThat(ids(home.get("topDeals"))).contains(course);
        assertThat(ids(home.get("priceDrops"))).contains(course).doesNotContain(app);
        assertThat(ids(home.get("trending"))).contains(course);
    }

    private int priceDropMails(String title) {
        Integer n = jdbc.queryForObject("select count(*) from notification_log where kind = 'PRICE_DROP' and recipient = ? and subject = ?",
                Integer.class, "deals-saver@test.local", "Price drop: " + title);
        return n == null ? 0 : n;
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 50 && !condition.getAsBoolean(); i++) Thread.sleep(100);
        assertThat(condition.getAsBoolean()).as("condition within 5 seconds").isTrue();
    }

    @Test
    void stalePriceIsHiddenAndOlderHistoryDoesNotCountAsADrop() throws Exception {
        MockHttpSession admin = login();
        long id = createPublished(admin, "Deals Stale Template", "https://www.etsy.com/listing/deals-1", 90000L, null);
        jdbc.update("update affiliate_links set price_checked_at = ? where product_id = ?",
                LocalDateTime.ofInstant(Instant.now().minus(30, ChronoUnit.DAYS), ZoneOffset.UTC), id);
        jdbc.update("update affiliate_price_history set recorded_at = ? where product_id = ?",
                LocalDateTime.ofInstant(Instant.now().minus(40, ChronoUnit.DAYS), ZoneOffset.UTC), id);
        JsonNode c = card(search("deals stale"), id);
        assertThat(c.hasNonNull("priceMinor")).isFalse();
        assertThat(c.get("checkPrice").asBoolean()).isTrue();
        assertThat(c.get("lowest30d").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private long createPublished(MockHttpSession admin, String title, String url, Long price, Long listPrice) throws Exception {
        JsonNode p = body(call(admin, post(ADMIN + "/products"), Map.of("productType", "DIGITAL", "sellingMethod", "AFFILIATE",
                "title", title, "deliveryKind", "DOWNLOAD")).andExpect(status().isCreated()));
        long id = p.get("id").asLong();
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        mvc.perform(multipart(ADMIN + "/products/" + id + "/images").file(new MockMultipartFile("file", "p.png", "image/png", out.toByteArray()))
                .session(admin).with(csrf())).andExpect(status().isOk());
        call(admin, put(ADMIN + "/products/" + id + "/affiliate-link"), link(url, price, listPrice, true)).andExpect(status().isOk());
        call(admin, post(ADMIN + "/products/" + id + "/status"), Map.of("status", "PUBLISHED")).andExpect(status().isOk());
        return id;
    }

    private void setLink(MockHttpSession admin, long id, String url, Long price, Long listPrice) throws Exception {
        call(admin, put(ADMIN + "/products/" + id + "/affiliate-link"), link(url, price, listPrice, false)).andExpect(status().isOk());
    }

    private static Map<String, Object> link(String url, Long price, Long listPrice, boolean confirm) {
        Map<String, Object> m = new HashMap<>();
        m.put("url", url);
        m.put("displayPriceMinor", price);
        m.put("listPriceMinor", listPrice);
        m.put("confirmNewDomain", confirm);
        return m;
    }

    private String slug(MockHttpSession admin, long id) throws Exception {
        return body(call(admin, get(ADMIN + "/products/" + id), null)).get("slug").asText();
    }

    private JsonNode search(String q) throws Exception {
        return body(call(new MockHttpSession(), get("/api/digital/catalog/products").param("q", q).param("size", "48"), null)
                .andExpect(status().isOk()));
    }

    private static JsonNode card(JsonNode page, long id) {
        for (JsonNode n : page.get("items")) if (n.get("id").asLong() == id) return n;
        throw new AssertionError("product " + id + " not listed in " + page);
    }

    private static List<Long> ids(JsonNode cards) {
        List<Long> out = new ArrayList<>();
        cards.forEach(c -> out.add(c.get("id").asLong()));
        return out;
    }

    private MockHttpSession login() throws Exception {
        MockHttpSession s = new MockHttpSession();
        call(s, post(ADMIN + "/auth/login"), Map.of("email", "deals-master@test.local", "password", PASSWORD)).andExpect(status().isOk());
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
