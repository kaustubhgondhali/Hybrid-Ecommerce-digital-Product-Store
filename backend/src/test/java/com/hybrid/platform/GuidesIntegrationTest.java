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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Buying guides: drafts and publishing, product cards, escaping, structured data, sitemap and moved addresses. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GuidesIntegrationTest {

    private static final String PASSWORD = "Guides-Test-Passw0rd-1";
    private static final String ADMIN = "/api/digital/admin";
    private static final String TEXT = "Picking a course is hard when there are thousands of them. We compared the most popular options "
            + "for Indian learners on price, depth, certificates and how much practice they include, so you can decide quickly.";

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
        if (admins.findByStorefrontAndEmailIgnoreCase(Storefront.DIGITAL, "guides-master@test.local").isEmpty()) {
            admins.save(new AdminUser(Storefront.DIGITAL, "guides-master@test.local", passwords.encode(PASSWORD), "Guides",
                    AdminRole.MASTER_ADMIN, false));
        }
    }

    @Test
    void writePublishAndShowGuides() throws Exception {
        MockHttpSession admin = login();
        long a = createPublished(admin, "Guides Test Java Masterclass", "https://www.udemy.com/course/guides-java/", 129900L);
        long b = createPublished(admin, "Guides Test SQL Bootcamp", "https://www.udemy.com/course/guides-sql/", 89900L);
        String slugA = body(call(admin, get(ADMIN + "/products/" + a), null)).get("slug").asText();
        String slugB = body(call(admin, get(ADMIN + "/products/" + b), null)).get("slug").asText();

        // ---- an unknown product is refused; a draft saves; it is not public yet
        Map<String, Object> in = new HashMap<>();
        in.put("title", "Best <script>alert(1)</script> coding courses in India");
        in.put("summary", "We compared the popular ones. These are worth your money.");
        in.put("body", "Intro\n\n[product: no-such-course]");
        call(admin, post(ADMIN + "/guides"), in).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GUIDE_PRODUCT_NOT_FOUND"));
        in.put("body", "Short intro.\n\n[products: " + slugA + ", " + slugB + "]");
        JsonNode draft = body(call(admin, post(ADMIN + "/guides"), in).andExpect(status().isCreated()));
        long id = draft.get("id").asLong();
        String slug = draft.get("slug").asText();
        assertThat(slug).isEqualTo("best-script-alert-1-script-coding-courses-in-india");
        assertThat(draft.get("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.get("productCount").asInt()).isEqualTo(2);
        mvc.perform(get("/digital/guides/" + slug)).andExpect(status().isNotFound());
        String preview = mvc.perform(get(ADMIN + "/guides/" + id + "/preview").session(admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(preview).contains("Preview of a draft").contains("guide-picks").contains("noindex");
        mvc.perform(get(ADMIN + "/guides/" + id + "/preview")).andExpect(status().isUnauthorized());

        // ---- too short to publish; then a proper guide is published
        call(admin, put(ADMIN + "/guides/" + id), Map.of("publish", true)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GUIDE_TOO_SHORT"));
        Map<String, Object> full = new HashMap<>();
        full.put("body", TEXT + "\n\n# Our top picks\n[products: " + slugA + ", " + slugB + "]\n\n# Best for Java\n"
                + "Deep and practical.\n[product: " + slugA + "]\n\n# Comparison\n| Course | Price |\n|---|---|\n| Java | Good |\n\n# How to choose\n- Budget\n- Time");
        full.put("authorName", "Asha Verma");
        full.put("publish", true);
        JsonNode live = body(call(admin, put(ADMIN + "/guides/" + id), full).andExpect(status().isOk()));
        assertThat(live.get("status").asText()).isEqualTo("PUBLISHED");

        String page = mvc.perform(get("/digital/guides/" + slug)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("&lt;script&gt;alert(1)&lt;/script&gt;").doesNotContain("<script>alert(1)</script>");
        assertThat(page).contains("class=\"guide-product\"").contains("class=\"guide-picks\"").contains("Guides Test Java Masterclass")
                .contains("href=\"/go/").contains("rel=\"sponsored nofollow noopener\"").contains("₹1,299")
                .contains("In this guide").contains("<h2 id=\"s-our-top-picks\">").contains("href=\"#s-how-to-choose\"")
                .contains("\"@type\":\"Article\"").contains("\"name\":\"Asha Verma\"").contains("By Asha Verma")
                .contains("We may earn a commission").contains("<table class=\"compare\">");

        // ---- lists: page, JSON (with cover), per product, sitemap
        assertThat(mvc.perform(get("/digital/guides")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .contains("Buying guides.").contains("/digital/guides/" + slug);
        JsonNode cards = body(mvc.perform(get("/api/digital/catalog/guides")).andExpect(status().isOk()));
        JsonNode card = null;
        for (JsonNode c : cards) if (c.get("slug").asText().equals(slug)) card = c;
        assertThat(card).isNotNull();
        assertThat(card.get("coverUrl").asText()).isNotBlank();
        assertThat(card.get("readMinutes").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(body(mvc.perform(get("/api/digital/catalog/guides").param("product", String.valueOf(b)))).toString()).contains(slug);
        assertThat(body(mvc.perform(get("/api/digital/catalog/guides").param("product", "999999"))).size()).isZero();
        assertThat(mvc.perform(get("/sitemap.xml")).andReturn().getResponse().getContentAsString()).contains("/digital/guides/" + slug);

        // ---- a published guide that moves keeps its old links working; drafts and deleted guides disappear
        JsonNode moved = body(call(admin, put(ADMIN + "/guides/" + id), Map.of("slug", "best-coding-courses")).andExpect(status().isOk()));
        assertThat(moved.get("url").asText()).isEqualTo("/digital/guides/best-coding-courses");
        mvc.perform(get("/digital/guides/" + slug)).andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "/digital/guides/best-coding-courses"));
        call(admin, put(ADMIN + "/guides/" + id), Map.of("slug", "Not A Slug!")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_GUIDE_SLUG"));
        call(admin, put(ADMIN + "/guides/" + id), Map.of("publish", false)).andExpect(status().isOk());
        mvc.perform(get("/digital/guides/best-coding-courses")).andExpect(status().isNotFound());
        call(admin, delete(ADMIN + "/guides/" + id), null).andExpect(status().isNoContent());
        call(admin, get(ADMIN + "/guides/" + id), null).andExpect(status().isNotFound());
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
        call(s, post(ADMIN + "/auth/login"), Map.of("email", "guides-master@test.local", "password", PASSWORD)).andExpect(status().isOk());
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
