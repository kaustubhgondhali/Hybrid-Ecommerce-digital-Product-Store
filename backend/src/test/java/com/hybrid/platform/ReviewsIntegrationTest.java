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
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Customer reviews wait for approval; the store team can add reviews it received by WhatsApp, email, phone or in person. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReviewsIntegrationTest {

    private static final String PASSWORD = "Reviews-Test-Passw0rd-1";
    private static final String ADMIN = "/api/digital/admin";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    AdminUserRepository admins;
    @Autowired
    PasswordEncoder passwords;

    @BeforeEach
    void staff() {
        createAdmin(Storefront.DIGITAL, "reviews-master@test.local", AdminRole.MASTER_ADMIN);
        createAdmin(Storefront.DIGITAL, "reviews-support@test.local", AdminRole.SUPPORT);
        createAdmin(Storefront.SHOP, "reviews-shop@test.local", AdminRole.MASTER_ADMIN);
    }

    @Test
    void customerReviewsNeedApprovalAndStaffCanAddLabelledReviews() throws Exception {
        MockHttpSession admin = login("digital", "reviews-master@test.local");
        long product = createPublished(admin, "Reviews Test Video Editor", "https://www.udemy.com/course/reviews-video-editor/");
        String slug = body(call(admin, get(ADMIN + "/products/" + product), null)).get("slug").asText();
        String reviewsUrl = "/api/digital/catalog/products/" + slug + "/reviews";
        long pendingBefore = pending(admin);

        // ---- a customer's review goes to the approval queue and stays off the website
        MockHttpSession customer = new MockHttpSession();
        call(customer, post("/api/auth/register"), Map.of("email", "reviews-buyer@test.local", "password", "Buyer-Passw0rd1",
                "fullName", "Rahul Kumar Verma", "storefront", "digital")).andExpect(status().isCreated());
        call(customer, post(reviewsUrl), Map.of("rating", 5, "title", "Worth it", "body", "Exported my first reel in an hour."))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        call(new MockHttpSession(), get(reviewsUrl), null)
                .andExpect(jsonPath("$.reviews.items.length()").value(0))
                .andExpect(jsonPath("$.rating.count").value(0));
        call(customer, get(reviewsUrl), null).andExpect(jsonPath("$.mine.status").value("PENDING"));
        assertThat(pending(admin)).isEqualTo(pendingBefore + 1);

        JsonNode queued = find(body(call(admin, get(ADMIN + "/reviews").param("status", "PENDING"), null)), product, "Rahul V.");
        assertThat(queued.get("source").asText()).isEqualTo("WEBSITE");
        assertThat(queued.hasNonNull("sourceLabel")).isFalse();
        assertThat(queued.hasNonNull("addedBy")).isFalse();

        // ---- approving it (with a reply) publishes it
        call(admin, patch(ADMIN + "/reviews/" + queued.get("id").asLong()), Map.of("status", "APPROVED", "adminReply", "Thanks, Rahul!"))
                .andExpect(status().isNoContent());
        assertThat(pending(admin)).isEqualTo(pendingBefore);
        call(new MockHttpSession(), get(reviewsUrl), null)
                .andExpect(jsonPath("$.reviews.items.length()").value(1))
                .andExpect(jsonPath("$.reviews.items[0].authorName").value("Rahul V."))
                .andExpect(jsonPath("$.reviews.items[0].adminReply").value("Thanks, Rahul!"))
                .andExpect(jsonPath("$.reviews.items[0].sourceLabel").doesNotExist())
                .andExpect(jsonPath("$.rating.count").value(1));

        // ---- the team adds a review a customer sent on WhatsApp: published at once, labelled, never "verified"
        JsonNode added = body(call(admin, post(ADMIN + "/reviews"), staffReview(product, "Priya Sharma", 4, "WHATSAPP", null))
                .andExpect(status().isCreated()));
        assertThat(added.get("status").asText()).isEqualTo("APPROVED");
        assertThat(added.get("authorName").asText()).isEqualTo("Priya S.");
        assertThat(added.get("source").asText()).isEqualTo("WHATSAPP");
        assertThat(added.get("sourceLabel").asText()).isEqualTo("Shared with us on WhatsApp");
        assertThat(added.get("addedBy").asText()).isEqualTo("reviews-master@test.local");
        assertThat(added.get("verifiedPurchase").asBoolean()).isFalse();

        JsonNode page = body(call(new MockHttpSession(), get(reviewsUrl), null));
        assertThat(page.get("rating").get("count").asInt()).isEqualTo(2);
        assertThat(page.get("rating").get("average").asDouble()).isEqualTo(4.5);
        JsonNode shown = null;
        for (JsonNode r : page.get("reviews").get("items")) if (r.get("authorName").asText().equals("Priya S.")) shown = r;
        assertThat(shown).isNotNull();
        assertThat(shown.get("sourceLabel").asText()).isEqualTo("Shared with us on WhatsApp");
        assertThat(shown.get("verifiedPurchase").asBoolean()).isFalse();
        String html = mvc.perform(get("/digital/p/" + slug)).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("\"reviewCount\":2").contains("Priya S.");

        // ---- "keep waiting" puts a team-added review in the queue instead
        call(admin, post(ADMIN + "/reviews"), staffReview(product, "Anil", 3, "phone", false))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.authorName").value("Anil"))
                .andExpect(jsonPath("$.sourceLabel").value("Shared with us by phone"));
        assertThat(pending(admin)).isEqualTo(pendingBefore + 1);
        call(new MockHttpSession(), get(reviewsUrl), null).andExpect(jsonPath("$.reviews.items.length()").value(2));

        // ---- validation
        call(admin, post(ADMIN + "/reviews"), staffReview(product, "Meena", 5, null, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SOURCE_REQUIRED"));
        call(admin, post(ADMIN + "/reviews"), staffReview(product, "Meena", 5, "WEBSITE", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SOURCE_REQUIRED"));
        call(admin, post(ADMIN + "/reviews"), staffReview(product, "Meena", 6, "EMAIL", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RATING"));
        call(admin, post(ADMIN + "/reviews"), staffReview(product, "  ", 5, "EMAIL", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AUTHOR_REQUIRED"));
        Map<String, Object> noText = staffReview(product, "Meena", 5, "EMAIL", null);
        noText.put("body", "   ");
        call(admin, post(ADMIN + "/reviews"), noText)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REVIEW_TEXT_REQUIRED"));
        call(admin, post(ADMIN + "/reviews"), staffReview(999_999_999L, "Meena", 5, "EMAIL", null))
                .andExpect(status().isNotFound());

        // ---- another storefront's admin cannot review this product; read-only staff cannot add reviews
        MockHttpSession shopAdmin = login("shop", "reviews-shop@test.local");
        call(shopAdmin, post("/api/shop/admin/reviews"), staffReview(product, "Meena", 5, "EMAIL", null))
                .andExpect(status().isNotFound());
        MockHttpSession support = login("digital", "reviews-support@test.local");
        call(support, get(ADMIN + "/reviews/pending-count"), null).andExpect(status().isOk());
        call(support, post(ADMIN + "/reviews"), staffReview(product, "Meena", 5, "EMAIL", null))
                .andExpect(status().isForbidden());
        call(new MockHttpSession(), post(ADMIN + "/reviews"), staffReview(product, "Meena", 5, "EMAIL", null))
                .andExpect(status().isUnauthorized());
        assertThat(pending(admin)).isEqualTo(pendingBefore + 1);
    }

    private static Map<String, Object> staffReview(long productId, String name, int rating, String source, Boolean publish) {
        Map<String, Object> m = new HashMap<>();
        m.put("productId", productId);
        m.put("authorName", name);
        m.put("rating", rating);
        m.put("title", "Shared with the team");
        m.put("body", "Bought it after reading your page. Works well on my laptop.");
        m.put("source", source);
        m.put("publish", publish);
        return m;
    }

    private long pending(MockHttpSession admin) throws Exception {
        return body(call(admin, get(ADMIN + "/reviews/pending-count"), null).andExpect(status().isOk())).get("pending").asLong();
    }

    private static JsonNode find(JsonNode page, long productId, String author) {
        for (JsonNode r : page.get("items")) {
            if (r.get("productId").asLong() == productId && r.get("authorName").asText().equals(author)) return r;
        }
        throw new AssertionError("review by " + author + " not in " + page);
    }

    private long createPublished(MockHttpSession admin, String title, String url) throws Exception {
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
        link.put("displayPriceMinor", 99900L);
        link.put("confirmNewDomain", true);
        call(admin, put(ADMIN + "/products/" + id + "/affiliate-link"), link).andExpect(status().isOk());
        call(admin, post(ADMIN + "/products/" + id + "/status"), Map.of("status", "PUBLISHED")).andExpect(status().isOk());
        return id;
    }

    private void createAdmin(Storefront sf, String email, AdminRole role) {
        if (admins.findByStorefrontAndEmailIgnoreCase(sf, email).isEmpty()) {
            admins.save(new AdminUser(sf, email, passwords.encode(PASSWORD), "Reviews", role, false));
        }
    }

    private MockHttpSession login(String sf, String email) throws Exception {
        MockHttpSession s = new MockHttpSession();
        call(s, post("/api/" + sf + "/admin/auth/login"), Map.of("email", email, "password", PASSWORD)).andExpect(status().isOk());
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
