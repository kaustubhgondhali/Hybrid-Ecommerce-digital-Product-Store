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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Admin role permissions, the /go redirect, conversion postbacks, stale partner prices and price-drop alerts. */
@SpringBootTest(properties = "app.affiliate.postback-token=" + AffiliateAdminIntegrationTest.POSTBACK_TOKEN)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AffiliateAdminIntegrationTest {

    static final String POSTBACK_TOKEN = "test-only-postback-token";
    private static final String PASSWORD = "Aff-Test-Passw0rd-1";
    private static final String UA = "Mozilla/5.0 (integration test)";

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
        ensureAdmin("aff-master@test.local", AdminRole.MASTER_ADMIN);
        ensureAdmin("aff-catalog@test.local", AdminRole.CATALOG_MANAGER);
        ensureAdmin("aff-support@test.local", AdminRole.SUPPORT);
    }

    @Test
    void rolesOnlyGetTheirOwnPermissions() throws Exception {
        call(new MockHttpSession(), get("/api/shop/admin/products"), null).andExpect(status().isUnauthorized());

        MockHttpSession support = login("aff-support@test.local");
        call(support, get("/api/shop/admin/products"), null).andExpect(status().isOk());
        call(support, get("/api/shop/admin/orders"), null).andExpect(status().isOk());
        call(support, get("/api/shop/admin/affiliate/links"), null).andExpect(status().isOk());
        call(support, get("/api/shop/admin/research"), null).andExpect(status().isOk());
        call(support, post("/api/shop/admin/products"), Map.of("productType", "PHYSICAL", "sellingMethod", "DIRECT",
                "title", "Not Allowed", "priceMinor", 100, "sku", "NO-1")).andExpect(status().isForbidden());
        call(support, put("/api/shop/admin/products/1/affiliate-link"), Map.of("url", "https://www.amazon.in/dp/X"))
                .andExpect(status().isForbidden());
        call(support, post("/api/shop/admin/products/1/affiliate-link/confirm-price"), null).andExpect(status().isForbidden());
        call(support, post("/api/shop/admin/affiliate/conversions/import"), Map.of("csv", "x")).andExpect(status().isForbidden());
        call(support, get("/api/shop/admin/staff"), null).andExpect(status().isForbidden());
        call(support, get("/api/digital/admin/products"), null).andExpect(status().isUnauthorized());

        MockHttpSession catalog = login("aff-catalog@test.local");
        call(catalog, get("/api/shop/admin/affiliate/links"), null).andExpect(status().isOk());
        call(catalog, get("/api/shop/admin/orders"), null).andExpect(status().isForbidden());
        call(catalog, get("/api/shop/admin/staff"), null).andExpect(status().isForbidden());
    }

    @Test
    void affiliateRedirectPostbackStalePriceAndPriceDropAlert() throws Exception {
        MockHttpSession master = login("aff-master@test.local");
        JsonNode product = body(call(master, post("/api/shop/admin/products"), Map.of(
                "productType", "PHYSICAL", "sellingMethod", "AFFILIATE", "title", "Postback Test Speaker"))
                .andExpect(status().isCreated()));
        long productId = product.get("id").asLong();
        long variantId = product.get("variants").get(0).get("id").asLong();
        String slug = product.get("slug").asText();
        uploadImage(master, "/api/shop/admin/products/" + productId + "/images");
        String destination = "https://www.flipkart.com/test-speaker/p/itm0001";
        JsonNode link = body(call(master, put("/api/shop/admin/products/" + productId + "/affiliate-link"),
                Map.of("url", destination, "displayPriceMinor", 200000, "confirmNewDomain", true))
                .andExpect(status().isOk()));
        String code = link.get("code").asText();
        call(master, patch("/api/shop/admin/affiliate/merchants/" + link.get("merchantId").asLong()), Map.of("subIdParam", "subid"))
                .andExpect(status().isOk());
        call(master, post("/api/shop/admin/products/" + productId + "/status"), Map.of("status", "PUBLISHED"))
                .andExpect(status().isOk());

        // ---- /go: unknown codes go to the "unavailable" page, real ones to the merchant with a click reference
        mvc.perform(get("/go/zzzzzzzz").header("User-Agent", UA))
                .andExpect(status().isFound()).andExpect(header().string("Location", "/link-unavailable.html"));
        String location = mvc.perform(get("/go/" + code).header("User-Agent", UA))
                .andExpect(status().isFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"))
                .andReturn().getResponse().getHeader("Location");
        assertThat(location).startsWith(destination + "?subid=c");
        Matcher m = Pattern.compile("subid=(c[a-z0-9]+)").matcher(location);
        assertThat(m.find()).isTrue();
        String clickRef = m.group(1);

        // ---- postback: needs the shared token, is idempotent per transaction id and links to the click
        Map<String, String> params = new HashMap<>(Map.of("txn", "PB-TXN-1", "click_ref", clickRef,
                "sale", "1999.00", "commission", "120.50", "status", "approved"));
        postback(params, null).andExpect(status().isForbidden());
        postback(params, "wrong-token").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("POSTBACK_TOKEN_INVALID"));
        postback(params, POSTBACK_TOKEN).andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.matchedClick").value(true))
                .andExpect(jsonPath("$.status").value("APPROVED"));
        postback(params, POSTBACK_TOKEN).andExpect(status().isOk()).andExpect(jsonPath("$.created").value(false));
        params.put("sale", "abc");
        postback(params, POSTBACK_TOKEN).andExpect(status().isBadRequest());

        JsonNode report = body(call(master, get("/api/shop/admin/affiliate/report"), null).andExpect(status().isOk()));
        JsonNode row = null;
        for (JsonNode r : report.get("products")) if (r.get("productId").asLong() == productId) row = r;
        assertThat(row).as("performance row").isNotNull();
        assertThat(row.get("clicks30d").asLong()).isEqualTo(1);
        assertThat(row.get("conversions30d").asLong()).isEqualTo(1);
        assertThat(row.get("commission30dMinor").asLong()).isEqualTo(12050);

        // ---- a partner price that has not been re-checked is hidden from the storefront until confirmed
        call(new MockHttpSession(), get("/api/shop/catalog/products/" + slug), null)
                .andExpect(jsonPath("$.affiliate.displayPriceMinor").value(200000))
                .andExpect(jsonPath("$.affiliate.priceStale").value(false));
        jdbc.update("update affiliate_links set price_checked_at = ? where product_id = ?",
                Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)), productId);
        call(new MockHttpSession(), get("/api/shop/catalog/products/" + slug), null)
                .andExpect(jsonPath("$.affiliate.displayPriceMinor").doesNotExist())
                .andExpect(jsonPath("$.affiliate.priceStale").value(true));
        call(master, post("/api/shop/admin/products/" + productId + "/affiliate-link/confirm-price"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.priceStale").value(false));
        call(new MockHttpSession(), get("/api/shop/catalog/products/" + slug), null)
                .andExpect(jsonPath("$.affiliate.displayPriceMinor").value(200000));

        // ---- customers who saved the deal get one price-drop alert, not one per edit
        MockHttpSession buyer = new MockHttpSession();
        call(buyer, post("/api/auth/register"), Map.of("email", "saver@test.local", "password", "Saver-Passw0rd1",
                "fullName", "Deal Saver", "storefront", "shop")).andExpect(status().isCreated());
        call(buyer, post("/api/shop/cart/items"), Map.of("variantId", variantId)).andExpect(status().isOk());

        setPrice(master, productId, destination, 195000);
        Thread.sleep(300);
        assertThat(priceDropMails("saver@test.local")).as("drop under 5% sends nothing").isZero();

        setPrice(master, productId, destination, 150000);
        waitFor(() -> priceDropMails("saver@test.local") == 1);
        setPrice(master, productId, destination, 120000);
        Thread.sleep(500);
        assertThat(priceDropMails("saver@test.local")).as("cooldown").isEqualTo(1);
    }

    private void setPrice(MockHttpSession admin, long productId, String url, long priceMinor) throws Exception {
        call(admin, put("/api/shop/admin/products/" + productId + "/affiliate-link"),
                Map.of("url", url, "displayPriceMinor", priceMinor)).andExpect(status().isOk());
    }

    private int priceDropMails(String email) {
        Integer n = jdbc.queryForObject("select count(*) from notification_log where kind = 'PRICE_DROP' and recipient = ?",
                Integer.class, email);
        return n == null ? 0 : n;
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 50 && !condition.getAsBoolean(); i++) Thread.sleep(100);
        assertThat(condition.getAsBoolean()).as("condition within 5 seconds").isTrue();
    }

    private ResultActions postback(Map<String, String> params, String token) throws Exception {
        MockHttpServletRequestBuilder req = post("/api/shop/affiliate/postback").accept(MediaType.APPLICATION_JSON);
        params.forEach(req::param);
        if (token != null) req.header("X-Postback-Token", token);
        return mvc.perform(req);
    }

    private void ensureAdmin(String email, AdminRole role) {
        if (admins.findByStorefrontAndEmailIgnoreCase(Storefront.SHOP, email).isEmpty()) {
            admins.save(new AdminUser(Storefront.SHOP, email, passwords.encode(PASSWORD), role.name(), role, false));
        }
    }

    private MockHttpSession login(String email) throws Exception {
        MockHttpSession s = new MockHttpSession();
        call(s, post("/api/shop/admin/auth/login"), Map.of("email", email, "password", PASSWORD)).andExpect(status().isOk());
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

    private void uploadImage(MockHttpSession session, String url) throws Exception {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        mvc.perform(multipart(url).file(new MockMultipartFile("file", "p.png", "image/png", out.toByteArray()))
                        .session(session).with(csrf()))
                .andExpect(status().isOk());
    }
}
