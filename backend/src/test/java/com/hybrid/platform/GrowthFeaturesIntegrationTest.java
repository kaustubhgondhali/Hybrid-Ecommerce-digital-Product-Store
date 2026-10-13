package com.hybrid.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.crypto.Hashing;
import com.hybrid.platform.crm.SubscriptionRepositories;
import com.hybrid.platform.crm.SubscriptionService;
import com.hybrid.platform.identity.AdminRole;
import com.hybrid.platform.identity.AdminUser;
import com.hybrid.platform.identity.AdminUserRepository;
import com.hybrid.platform.notification.NotificationRepositories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Coupons and store pages, price-drop alerts, newsletter sign-up, Deal of the Day and the WhatsApp number. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GrowthFeaturesIntegrationTest {

    private static final String PASSWORD = "Growth-Test-Passw0rd-1";
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
    SubscriptionRepositories.PriceAlertRepository alerts;
    @Autowired
    SubscriptionRepositories.NewsletterRepository subscribers;
    @Autowired
    SubscriptionService subscriptions;
    @Autowired
    NotificationRepositories.NotificationLogRepository mails;

    @BeforeEach
    void staff() {
        if (admins.findByStorefrontAndEmailIgnoreCase(Storefront.DIGITAL, "growth-master@test.local").isEmpty()) {
            admins.save(new AdminUser(Storefront.DIGITAL, "growth-master@test.local", passwords.encode(PASSWORD), "Growth",
                    AdminRole.MASTER_ADMIN, false));
        }
    }

    @Test
    void couponsAlertsNewsletterDealOfDay() throws Exception {
        MockHttpSession admin = login();
        long course = createPublished(admin, "Growth Test Kotlin Course", "https://www.udemy.com/course/growth-kotlin/", 99900L);
        String slug = body(call(admin, get(ADMIN + "/products/" + course), null)).get("slug").asText();

        // ---- coupon offers: approved store only, listed per store and product, tracked redirect, expiry
        Map<String, Object> offer = new HashMap<>();
        offer.put("url", "https://www.udemy.com/courses/growth-sale/");
        offer.put("title", "Growth Test: 20% off every course");
        offer.put("code", "grow20");
        offer.put("verified", true);
        JsonNode created = body(call(admin, post(ADMIN + "/offers"), offer).andExpect(status().isCreated()));
        long offerId = created.get("id").asLong();
        assertThat(created.get("code").asText()).isEqualTo("GROW20");
        assertThat(created.get("live").asBoolean()).isTrue();
        Map<String, Object> unknown = new HashMap<>(offer);
        unknown.put("url", "https://growth-unknown-store.example/sale");
        call(admin, post(ADMIN + "/offers"), unknown).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AFFILIATE_DOMAIN_UNCONFIRMED"));
        Map<String, Object> expired = new HashMap<>(offer);
        expired.put("title", "Growth Test: old sale");
        expired.put("code", null);
        expired.put("startsAt", Instant.now().minus(3, ChronoUnit.DAYS).toString());
        expired.put("endsAt", Instant.now().minus(1, ChronoUnit.DAYS).toString());
        long expiredId = body(call(admin, post(ADMIN + "/offers"), expired).andExpect(status().isCreated())).get("id").asLong();

        String live = body(call(new MockHttpSession(), get("/api/digital/catalog/offers"), null)).toString();
        assertThat(live).contains("Growth Test: 20% off every course").doesNotContain("Growth Test: old sale");
        call(new MockHttpSession(), get("/api/digital/catalog/offers").param("product", String.valueOf(course)), null)
                .andExpect(jsonPath("$[?(@.id == " + offerId + ")].code").value("GROW20"));
        mvc.perform(get("/go/o/" + offerId).header("User-Agent", "Mozilla/5.0 (Linux; Android 14) Chrome/128 Mobile"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://www.udemy.com/courses/growth-sale/"));
        mvc.perform(get("/go/o/" + offerId).header("User-Agent", "Googlebot/2.1 (+http://www.google.com/bot.html)"))
                .andExpect(status().isFound());
        mvc.perform(get("/go/o/" + expiredId)).andExpect(status().isFound()).andExpect(header().string("Location", "/link-unavailable.html"));
        call(admin, get(ADMIN + "/offers"), null).andExpect(jsonPath("$[?(@.id == " + offerId + ")].clicks").value(1));

        String offersPage = mvc.perform(get("/digital/offers")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(offersPage).contains("Coupons &amp; offers").contains("data-offer-code=\"GROW20\"").contains("/go/o/" + offerId);
        String storePage = mvc.perform(get("/digital/store/udemy.com")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(storePage).contains("udemy.com deals &amp; coupons").contains("Growth Test Kotlin Course");
        mvc.perform(get("/digital/store/not-a-partner.example")).andExpect(status().isNotFound());
        String sitemap = mvc.perform(get("/sitemap.xml")).andReturn().getResponse().getContentAsString();
        assertThat(sitemap).contains("/digital/offers</loc>").contains("/digital/store/udemy.com</loc>");

        // ---- price-drop alert: below today's price only, confirmed by email, sent once when the price is reached
        call(new MockHttpSession(), post("/api/digital/price-alerts"), Map.of("slug", slug, "email", "Alert.Buyer@Test.local", "targetPrice", 999))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TARGET"));
        call(new MockHttpSession(), post("/api/digital/price-alerts"), Map.of("slug", slug, "email", "nope", "targetPrice", 900))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_EMAIL"));
        call(new MockHttpSession(), post("/api/digital/price-alerts"), Map.of("slug", slug, "email", "Alert.Buyer@Test.local", "targetPrice", 900))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CHECK_EMAIL")).andExpect(jsonPath("$.targetMinor").value(90000));
        assertThat(mails.findAll().stream().anyMatch(m -> "PRICE_ALERT_CONFIRM".equals(m.getKind()) && "alert.buyer@test.local".equals(m.getRecipient()))).isTrue();
        var alert = alerts.findByProductIdAndEmail(course, "alert.buyer@test.local").orElseThrow();
        alert.setTokenHash(Hashing.sha256Hex("growth-alert-token"));
        alerts.save(alert);
        mvc.perform(get("/api/price-alerts/confirm").param("t", "growth-alert-token")).andExpect(status().isFound())
                .andExpect(header().string("Location", "/digital/p/" + slug + "?alert=on"));
        subscriptions.checkAlerts();
        assertThat(alerts.findById(alert.getId()).orElseThrow().getNotifiedAt()).as("price not reached yet").isNull();
        Map<String, Object> cut = new HashMap<>();
        cut.put("url", "https://www.udemy.com/course/growth-kotlin/");
        cut.put("displayPriceMinor", 79900L);
        call(admin, put(ADMIN + "/products/" + course + "/affiliate-link"), cut).andExpect(status().isOk());
        subscriptions.checkAlerts();
        assertThat(alerts.findById(alert.getId()).orElseThrow().getNotifiedAt()).isNotNull();
        assertThat(mails.findAll().stream().anyMatch(m -> "PRICE_ALERT".equals(m.getKind()) && m.getSubject().contains("Growth Test Kotlin Course"))).isTrue();
        mvc.perform(get("/api/price-alerts/cancel").param("t", "growth-alert-token")).andExpect(status().isFound());
        assertThat(alerts.findById(alert.getId())).isEmpty();
        mvc.perform(get("/api/price-alerts/confirm").param("t", "wrong")).andExpect(header().string("Location", "/link-unavailable.html"));

        // ---- newsletter: confirmed by email, then active; one-click unsubscribe
        long before = subscriptions.subscriberCount(Storefront.DIGITAL);
        call(new MockHttpSession(), post("/api/digital/newsletter"), Map.of("email", "reader@test.local", "source", "home"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CHECK_EMAIL"));
        assertThat(subscriptions.subscriberCount(Storefront.DIGITAL)).as("not active before confirming").isEqualTo(before);
        var sub = subscribers.findByStorefrontAndEmail(Storefront.DIGITAL, "reader@test.local").orElseThrow();
        sub.setTokenHash(Hashing.sha256Hex("growth-news-token"));
        subscribers.save(sub);
        mvc.perform(get("/api/newsletter/confirm").param("t", "growth-news-token"))
                .andExpect(header().string("Location", "/digital?newsletter=on"));
        assertThat(subscriptions.subscriberCount(Storefront.DIGITAL)).isEqualTo(before + 1);
        call(new MockHttpSession(), post("/api/digital/newsletter"), Map.of("email", "READER@test.local"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(get("/api/newsletter/unsubscribe").param("t", "growth-news-token"))
                .andExpect(header().string("Location", "/digital?newsletter=off"));
        assertThat(subscriptions.subscriberCount(Storefront.DIGITAL)).isEqualTo(before);

        // ---- Deal of the Day with a real end time
        String ends = Instant.now().plus(5, ChronoUnit.HOURS).toString();
        Map<String, Object> dotd = new HashMap<>();
        dotd.put("productId", course);
        dotd.put("endsAt", ends);
        call(admin, put(ADMIN + "/deal-of-the-day"), dotd).andExpect(status().isOk());
        call(new MockHttpSession(), get("/api/digital/catalog/home"), null)
                .andExpect(jsonPath("$.dealOfDay.product.id").value(course));
        dotd.put("endsAt", Instant.now().minus(1, ChronoUnit.HOURS).toString());
        call(admin, put(ADMIN + "/deal-of-the-day"), dotd).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DEAL_END_REQUIRED"));
        Map<String, Object> clear = new HashMap<>();
        clear.put("productId", null);
        call(admin, put(ADMIN + "/deal-of-the-day"), clear).andExpect(status().isOk());
        call(new MockHttpSession(), get("/api/digital/catalog/home"), null).andExpect(jsonPath("$.dealOfDay").doesNotExist());

        // ---- WhatsApp chat number (Indian 10-digit numbers get the country code)
        call(admin, put(ADMIN + "/settings/store"), Map.of("whatsappNumber", "98765 43210")).andExpect(status().isOk());
        call(new MockHttpSession(), get("/api/digital/catalog/store"), null)
                .andExpect(jsonPath("$.store.whatsappNumber").value("919876543210"));
        call(admin, put(ADMIN + "/settings/store"), Map.of("whatsappNumber", "call me")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_WHATSAPP"));
        call(admin, put(ADMIN + "/settings/store"), Map.of("whatsappNumber", "")).andExpect(status().isOk());

        // ---- Google Analytics 4 and Meta Pixel IDs (loaded by the storefront only after cookie consent)
        call(admin, put(ADMIN + "/settings/store"), Map.of("ga4Id", "UA-12345-1")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_GA4_ID"));
        call(admin, put(ADMIN + "/settings/store"), Map.of("metaPixelId", "12ab")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PIXEL_ID"));
        call(admin, put(ADMIN + "/settings/store"), Map.of("ga4Id", " g-abc123xyz9 ", "metaPixelId", "1234567890123456")).andExpect(status().isOk());
        call(new MockHttpSession(), get("/api/digital/catalog/store"), null)
                .andExpect(jsonPath("$.store.ga4Id").value("G-ABC123XYZ9"))
                .andExpect(jsonPath("$.store.metaPixelId").value("1234567890123456"));
        call(admin, put(ADMIN + "/settings/store"), Map.of("ga4Id", "", "metaPixelId", "")).andExpect(status().isOk());
        call(new MockHttpSession(), get("/api/digital/catalog/store"), null).andExpect(jsonPath("$.store.ga4Id").doesNotExist());
        // storefront pages allow the two analytics hosts; admin and API responses stay strict
        assertThat(mvc.perform(get("/digital")).andReturn().getResponse().getHeader("Content-Security-Policy"))
                .contains("script-src 'self' https://*.googletagmanager.com https://connect.facebook.net");
        assertThat(mvc.perform(get(ADMIN + "/offers").session(admin)).andReturn().getResponse().getHeader("Content-Security-Policy"))
                .contains("script-src 'self';").doesNotContain("googletagmanager");

        // ---- installable app: manifest per storefront, service worker and offline page
        mvc.perform(get("/digital/manifest.webmanifest")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/manifest+json")))
                .andExpect(jsonPath("$.name").value("Elaatech"))
                .andExpect(jsonPath("$.start_url").value("/digital?source=app"))
                .andExpect(jsonPath("$.scope").value("/digital"))
                .andExpect(jsonPath("$.display").value("standalone"))
                .andExpect(jsonPath("$.icons[0].sizes").value("192x192"));
        mvc.perform(get("/shop/manifest.webmanifest")).andExpect(jsonPath("$.icons[1].src").value("/assets/img/elaashop-logo-square.png"));
        mvc.perform(get("/sw.js")).andExpect(status().isOk());
        mvc.perform(get("/offline.html")).andExpect(status().isOk());
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

    private MockHttpSession login() throws Exception {
        MockHttpSession s = new MockHttpSession();
        call(s, post(ADMIN + "/auth/login"), Map.of("email", "growth-master@test.local", "password", PASSWORD)).andExpect(status().isOk());
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
