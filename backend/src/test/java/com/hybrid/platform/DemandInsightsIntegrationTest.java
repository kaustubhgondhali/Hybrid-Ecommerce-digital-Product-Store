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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Sales &amp; demand research: views, searches, clicks, saves, alerts and sales ranked per period. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DemandInsightsIntegrationTest {

    private static final String PASSWORD = "Demand-Test-Passw0rd-1";
    private static final String ADMIN = "/api/digital/admin";
    private static final String BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/128 Safari/537.36";

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
        for (var who : List.of(Map.entry("demand-master@test.local", AdminRole.MASTER_ADMIN),
                Map.entry("demand-orders@test.local", AdminRole.ORDER_MANAGER))) {
            if (admins.findByStorefrontAndEmailIgnoreCase(Storefront.DIGITAL, who.getKey()).isEmpty()) {
                admins.save(new AdminUser(Storefront.DIGITAL, who.getKey(), passwords.encode(PASSWORD), "Demand", who.getValue(), false));
            }
        }
    }

    @Test
    void ranksProductsByRealShopperActivity() throws Exception {
        MockHttpSession admin = login("demand-master@test.local");
        long hot = createPublished(admin, "Demand Test Python Bootcamp", "https://www.coursera.org/learn/demand-python", 149900L);
        long quiet = createPublished(admin, "Demand Test Excel Basics", "https://www.coursera.org/learn/demand-excel", 49900L);
        String hotSlug = body(call(admin, get(ADMIN + "/products/" + hot), null)).get("slug").asText();
        String quietSlug = body(call(admin, get(ADMIN + "/products/" + quiet), null)).get("slug").asText();

        // ---- product page views: browsers only, unknown products ignored
        for (int i = 0; i < 3; i++) view(hotSlug, BROWSER).andExpect(status().isNoContent());
        view(quietSlug, BROWSER).andExpect(status().isNoContent());
        view(hotSlug, "Googlebot/2.1 (+http://www.google.com/bot.html)").andExpect(status().isNoContent());
        view("no-such-product", BROWSER).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select sum(views) from product_view_days where product_id = ?", Long.class, hot)).isEqualTo(3L);

        // ---- searches: first page from a browser counts once; empty results are remembered
        long found = search("  Demand   TEST python ", 0, BROWSER).get("totalItems").asLong();
        search("demand test python", 1, BROWSER);
        search("demand test python", 0, "curl/8.4");
        assertThat(search("demand test blockchain basics", 0, BROWSER).get("totalItems").asLong()).isZero();

        // ---- clicks to the store (two shoppers and a link preview bot), a save, a price alert, a sale and a reversed sale
        String go = body(mvc.perform(get("/api/digital/catalog/products/" + hotSlug))).findValue("goUrl").asText();
        mvc.perform(get(go).header("User-Agent", BROWSER)).andExpect(status().isFound());
        mvc.perform(get(go).header("User-Agent", BROWSER)).andExpect(status().isFound());
        mvc.perform(get(go).header("User-Agent", "facebookexternalhit/1.1")).andExpect(status().isFound());
        Map<String, Object> click = jdbc.queryForMap("select click_ref, link_id from affiliate_clicks where product_id = ? "
                + "and suspected_bot = false order by id limit 1", hot);
        Long merchant = jdbc.queryForObject("select merchant_id from affiliate_links where id = ?", Long.class, click.get("link_id"));
        Instant now = Instant.now(); // bound like the app binds instants
        String sale = "insert into affiliate_conversions (storefront, merchant_id, click_ref, external_txn_id, sale_amount_minor, "
                + "commission_minor, status, source, reported_at, updated_at) values ('DIGITAL', ?, ?, ?, 149900, 15000, ?, 'IMPORT', ?, ?)";
        jdbc.update(sale, merchant, click.get("click_ref"), "demand-ok-" + System.nanoTime(), "APPROVED", now, now);
        jdbc.update(sale, merchant, click.get("click_ref"), "demand-rev-" + System.nanoTime(), "REVERSED", now, now);
        jdbc.update("insert into wishlist_items (customer_id, storefront, product_id, created_at) values (?, 'DIGITAL', ?, ?)", 990001L, hot, now);
        jdbc.update("insert into price_alerts (storefront, product_id, email, target_minor, token_hash, confirmed_at, created_at) "
                + "values ('DIGITAL', ?, 'demand-alert@example.com', 120000, ?, ?, ?)", hot, "d".repeat(64), now, now);
        // yesterday at about this time: one click, so today is compared with it
        jdbc.update("insert into affiliate_clicks (click_ref, link_id, storefront, product_id, suspected_bot, created_at) "
                + "values (?, ?, 'DIGITAL', ?, false, ?)", "demand-y-" + System.nanoTime(), click.get("link_id"), hot, now.minus(Duration.ofDays(1)).minus(Duration.ofMinutes(1)));

        // ---- today's report
        JsonNode r = body(call(admin, get(ADMIN + "/insights/demand").param("period", "today"), null).andExpect(status().isOk()));
        assertThat(r.get("periodLabel").asText()).isEqualTo("Today");
        JsonNode row = find(r.get("inDemand"), hot);
        assertThat(row.get("views").asLong()).isEqualTo(3);
        assertThat(row.get("clicks").asLong()).isEqualTo(2);
        assertThat(row.get("saves").asLong()).isEqualTo(1);
        assertThat(row.get("alerts").asLong()).isEqualTo(1);
        assertThat(row.get("sales").asLong()).isEqualTo(1);
        assertThat(row.get("earnedMinor").asLong()).isEqualTo(15000);
        assertThat(row.get("score").asLong()).isEqualTo(3 + 2 * 5 + 3 + 3 + 20);
        assertThat(row.get("previousScore").asLong()).isEqualTo(5);
        assertThat(row.get("changePercent").asInt()).isEqualTo(680);
        assertThat(row.get("isNew").asBoolean()).isFalse();
        assertThat(index(r.get("inDemand"), hot)).isLessThan(index(r.get("inDemand"), quiet));
        assertThat(find(r.get("inDemand"), quiet).get("score").asLong()).isEqualTo(1);
        assertThat(find(r.get("bestSellers"), hot)).isNotNull();
        assertThat(find(r.get("rising"), hot)).isNotNull();
        assertThat(find(r.get("rising"), quiet)).isNull();
        assertThat(r.get("searches").toString())
                .contains("{\"term\":\"demand test python\",\"searches\":1,\"noResults\":" + (found == 0 ? 1 : 0) + "}");
        assertThat(r.get("notFound").toString()).contains("{\"term\":\"demand test blockchain basics\",\"searches\":1,\"noResults\":1}");
        assertThat(r.get("stores").toString()).contains("coursera.org");
        assertThat(r.get("totals").get("sales").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(r.get("totals").get("views").asLong()).isGreaterThanOrEqualTo(4);
        assertThat(r.get("trackingSince").asText()).isEqualTo(LocalDate.now(ZoneId.of("Asia/Kolkata")).toString());
        assertThat(r.get("showEarnings").asBoolean()).isTrue();
        JsonNode hours = r.get("activity");
        assertThat(hours.get(0).get("label").asText()).isEqualTo("00:00");
        assertThat(hours.get(0).has("views")).isFalse();
        long hourlyClicks = 0;
        for (JsonNode b : hours) hourlyClicks += b.get("clicks").asLong();
        assertThat(hourlyClicks).isGreaterThanOrEqualTo(2);

        // ---- longer periods include today; months for the long ones
        for (String p : List.of("week", "month", "6months", "year")) {
            JsonNode x = body(call(admin, get(ADMIN + "/insights/demand").param("period", p), null).andExpect(status().isOk()));
            assertThat(find(x.get("inDemand"), hot).get("score").asLong()).isGreaterThanOrEqualTo(39);
            assertThat(x.get("activity").size()).isGreaterThan(0);
        }
        JsonNode half = body(call(admin, get(ADMIN + "/insights/demand").param("period", "6months"), null));
        assertThat(half.get("activity").size()).isEqualTo(6);
        call(admin, get(ADMIN + "/insights/demand").param("period", "decade"), null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PERIOD"));

        // ---- staff without access to affiliate earnings see the demand but not the money; shoppers see nothing
        JsonNode limited = body(call(login("demand-orders@test.local"), get(ADMIN + "/insights/demand").param("period", "today"), null)
                .andExpect(status().isOk()));
        assertThat(limited.get("showEarnings").asBoolean()).isFalse();
        assertThat(find(limited.get("inDemand"), hot).has("earnedMinor")).isFalse();
        assertThat(limited.get("totals").has("earnedMinor")).isFalse();
        call(new MockHttpSession(), get(ADMIN + "/insights/demand"), null).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions view(String slug, String userAgent) throws Exception {
        return mvc.perform(post("/api/digital/catalog/products/" + slug + "/view").header("User-Agent", userAgent).with(csrf()));
    }

    private JsonNode search(String q, int page, String userAgent) throws Exception {
        return body(mvc.perform(get("/api/digital/catalog/products").param("q", q).param("page", String.valueOf(page))
                .header("User-Agent", userAgent)).andExpect(status().isOk()));
    }

    private static JsonNode find(JsonNode rows, long productId) {
        for (JsonNode r : rows) if (r.get("productId").asLong() == productId) return r;
        return null;
    }

    private static int index(JsonNode rows, long productId) {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).get("productId").asLong() == productId) return i;
        return Integer.MAX_VALUE;
    }

    private long createPublished(MockHttpSession admin, String title, String url, Long price) throws Exception {
        JsonNode p = body(call(admin, post(ADMIN + "/products"), Map.of("productType", "DIGITAL", "sellingMethod", "AFFILIATE",
                "title", title, "deliveryKind", "DOWNLOAD")).andExpect(status().isCreated()));
        long id = p.get("id").asLong();
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        mvc.perform(multipart(ADMIN + "/products/" + id + "/images").file(new MockMultipartFile("file", "p.png", "image/png", out.toByteArray()))
                .session(admin).with(csrf())).andExpect(status().isOk());
        Map<String, Object> link = new HashMap<>();
        link.put("url", url);
        link.put("displayPriceMinor", price);
        link.put("confirmNewDomain", true);
        call(admin, put(ADMIN + "/products/" + id + "/affiliate-link"), link).andExpect(status().isOk());
        call(admin, post(ADMIN + "/products/" + id + "/status"), Map.of("status", "PUBLISHED")).andExpect(status().isOk());
        return id;
    }

    private MockHttpSession login(String email) throws Exception {
        MockHttpSession s = new MockHttpSession();
        call(s, post(ADMIN + "/auth/login"), Map.of("email", email, "password", PASSWORD)).andExpect(status().isOk());
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
