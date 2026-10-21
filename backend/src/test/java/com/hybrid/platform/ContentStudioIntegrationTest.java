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
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Content studio: previews, creating guides / stories / newsletter drafts, posters, automation on publish, permissions. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContentStudioIntegrationTest {

    private static final String PASSWORD = "Studio-Test-Passw0rd-1";
    private static final String ADMIN = "/api/digital/admin";
    private static final String STUDIO = ADMIN + "/studio";

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
        createAdmin("studio-master@test.local", AdminRole.MASTER_ADMIN);
        createAdmin("studio-support@test.local", AdminRole.SUPPORT);
        createAdmin("studio-catalog@test.local", AdminRole.CATALOG_MANAGER);
    }

    @Test
    void generatesContentFromPublishedProducts() throws Exception {
        MockHttpSession admin = login("studio-master@test.local");
        long a = createPublished(admin, "Studio Test Focus Course", 149900L);
        long b = createPublished(admin, "Studio Test Habit Course", 99900L);
        long c = createPublished(admin, "Studio Test Sleep Course", 59900L);

        // ---- preview: blog drafts, story, newsletters and social posts written from the product's facts
        JsonNode preview = body(call(admin, get(STUDIO + "/products/" + a), null).andExpect(status().isOk()));
        assertThat(preview.get("product").get("productUrl").asText()).endsWith("/digital/p/studio-test-focus-course");
        assertThat(preview.get("blogs").size()).isEqualTo(4);
        assertThat(preview.get("blogs").get(0).get("body").asText()).contains("[product: studio-test-focus-course]")
                .contains("Deep work in 30 days").contains("₹1,499").doesNotContain("null");
        assertThat(preview.get("story").get("pages").size()).isGreaterThanOrEqualTo(3);
        assertThat(preview.get("storyReady").asBoolean()).isTrue();
        assertThat(preview.get("emails").get(0).get("kind").asText()).isEqualTo("LAUNCH");
        assertThat(preview.get("social").toString()).contains("whatsapp").contains("https://wa.me/?text=").contains("#");
        JsonNode other = body(call(admin, get(STUDIO + "/products/" + a).param("variant", "1"), null).andExpect(status().isOk()));
        assertThat(other.get("blogs").get(0).get("body").asText()).isNotEqualTo(preview.get("blogs").get(0).get("body").asText());

        // ---- one click: a published FAQ guide, a published story, a newsletter draft
        JsonNode blog = body(call(admin, post(STUDIO + "/blogs"), Map.of("productId", a, "kind", "FAQ", "publish", true))
                .andExpect(status().isCreated()));
        assertThat(blog.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(blog.get("adminPath").asText()).isEqualTo("#/guides/" + blog.get("id").asLong());
        JsonNode guide = body(call(admin, get(ADMIN + "/guides/" + blog.get("id").asLong()), null).andExpect(status().isOk()));
        mvc.perform(get(guide.get("url").asText())).andExpect(status().isOk());
        // the same draft again gets its own address instead of failing
        JsonNode again = body(call(admin, post(STUDIO + "/blogs"), Map.of("productId", a, "kind", "FAQ"))
                .andExpect(status().isCreated()));
        assertThat(again.get("status").asText()).isEqualTo("DRAFT");
        JsonNode againGuide = body(call(admin, get(ADMIN + "/guides/" + again.get("id").asLong()), null).andExpect(status().isOk()));
        assertThat(againGuide.get("slug").asText()).isEqualTo(guide.get("slug").asText() + "-2");

        JsonNode story = body(call(admin, post(STUDIO + "/stories"), Map.of("productId", a, "publish", true))
                .andExpect(status().isCreated()));
        assertThat(story.get("status").asText()).isEqualTo("PUBLISHED");
        JsonNode storyView = body(call(admin, get(ADMIN + "/stories/" + story.get("id").asLong()), null).andExpect(status().isOk()));
        assertThat(storyView.get("pages").size()).isGreaterThanOrEqualTo(3);
        assertThat(storyView.get("pages").get(0).get("productId").asLong()).isEqualTo(a);
        assertThat(storyView.get("coverUrl").asText()).startsWith("/media/");

        JsonNode mail = body(call(admin, post(STUDIO + "/newsletters"), Map.of("productIds", List.of(a), "kind", "LAUNCH"))
                .andExpect(status().isCreated()));
        assertThat(mail.get("status").asText()).isEqualTo("DRAFT");
        assertThat(mail.get("adminPath").asText()).startsWith("#/crm/campaigns/");

        // ---- several products: comparison, roundup, collection newsletter
        JsonNode multi = body(call(admin, post(STUDIO + "/collections/preview"), Map.of("productIds", List.of(a, b, c), "topic", "online courses"))
                .andExpect(status().isOk()));
        assertThat(multi.get("comparison").get("body").asText()).contains("| Price |");
        assertThat(multi.get("roundup").get("title").asText()).startsWith("Best online courses in");
        assertThat(multi.get("emails").size()).isEqualTo(3);
        call(admin, post(STUDIO + "/blogs"), Map.of("productIds", List.of(a, b, c), "kind", "ROUNDUP", "topic", "online courses"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.kind").value("BLOG"));
        call(admin, post(STUDIO + "/blogs"), Map.of("productIds", List.of(a), "kind", "COMPARISON"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CHOOSE_PRODUCTS"));
        call(admin, post(STUDIO + "/newsletters"), Map.of("productIds", List.of(a, b, c), "kind", "COLLECTION", "topic", "focus"))
                .andExpect(status().isCreated());

        // ---- posters: exact sizes, PNG
        BufferedImage square = png(mvc.perform(get(STUDIO + "/posters/" + a).param("format", "SQUARE").param("theme", "SALE").session(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(square.getWidth()).isEqualTo(1080);
        assertThat(square.getHeight()).isEqualTo(1080);
        BufferedImage pin = png(mvc.perform(get(STUDIO + "/posters/" + b).param("format", "PIN").session(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(pin.getHeight()).isEqualTo(1500);
        BufferedImage hero = png(mvc.perform(get(STUDIO + "/posters/collection").param("ids", a + "," + b + "," + c)
                        .param("format", "HERO").param("headline", "Learn something new").session(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(hero.getWidth()).isEqualTo(1920);
        assertThat(hero.getHeight()).isEqualTo(640);
        call(admin, get(STUDIO + "/posters/" + a).param("format", "POSTCARD"), null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_VALUE"));

        // ---- drafts are refused: content must link to a live page
        JsonNode draft = body(call(admin, post(ADMIN + "/products"), Map.of("productType", "DIGITAL", "sellingMethod", "AFFILIATE",
                "title", "Studio Test Draft Course", "deliveryKind", "DOWNLOAD")).andExpect(status().isCreated()));
        call(admin, get(STUDIO + "/products/" + draft.get("id").asLong()), null)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PRODUCT_NOT_PUBLISHED"));

        // ---- history lists what was made, newest first
        JsonNode history = body(call(admin, get(STUDIO + "/history"), null).andExpect(status().isOk()));
        assertThat(history.toString()).contains("\"kind\":\"BLOG\"").contains("\"kind\":\"STORY\"").contains("\"kind\":\"NEWSLETTER\"");

        // ---- permissions
        MockHttpSession support = login("studio-support@test.local");
        call(support, get(STUDIO + "/products/" + a), null).andExpect(status().isOk());
        call(support, post(STUDIO + "/blogs"), Map.of("productId", a)).andExpect(status().isForbidden());
        call(support, put(STUDIO + "/settings"), Map.of("autoBlog", "DRAFT")).andExpect(status().isForbidden());
        MockHttpSession catalog = login("studio-catalog@test.local");
        call(catalog, post(STUDIO + "/stories"), Map.of("productId", b)).andExpect(status().isCreated());
        call(catalog, post(STUDIO + "/newsletters"), Map.of("productIds", List.of(a))).andExpect(status().isForbidden());
        call(new MockHttpSession(), get(STUDIO + "/settings"), null).andExpect(status().isUnauthorized());
    }

    @Test
    void createsContentAutomaticallyWhenAProductIsPublished() throws Exception {
        MockHttpSession admin = login("studio-master@test.local");
        try {
            call(admin, put(STUDIO + "/settings"), Map.of("autoBlog", "DRAFT", "blogKind", "WORTH_IT", "autoStory", "PUBLISH", "autoNewsletter", true))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.autoStory").value("PUBLISH"));
            call(admin, put(STUDIO + "/settings"), Map.of("blogKind", "ROUNDUP", "autoNewsletter", true))
                    .andExpect(status().isBadRequest());
            long id = createPublished(admin, "Studio Test Auto Course", 79900L);

            JsonNode mine = null;
            for (int i = 0; i < 50; i++) {
                JsonNode history = body(call(admin, get(STUDIO + "/history"), null).andExpect(status().isOk()));
                List<JsonNode> rows = new java.util.ArrayList<>();
                history.forEach(r -> { if (r.get("productId").asLong() == id && r.get("automatic").asBoolean()) rows.add(r); });
                if (rows.size() == 3) {
                    mine = json.valueToTree(rows);
                    break;
                }
                Thread.sleep(100);
            }
            assertThat(mine).as("blog, story and newsletter made automatically").isNotNull();
            assertThat(mine.toString()).contains("BLOG").contains("STORY").contains("NEWSLETTER");
            for (JsonNode r : mine) {
                if (r.get("kind").asText().equals("BLOG")) {
                    call(admin, get(ADMIN + "/guides/" + r.get("targetId").asLong()), null).andExpect(status().isOk())
                            .andExpect(jsonPath("$.status").value("DRAFT"));
                }
                if (r.get("kind").asText().equals("STORY")) {
                    call(admin, get(ADMIN + "/stories/" + r.get("targetId").asLong()), null).andExpect(status().isOk())
                            .andExpect(jsonPath("$.status").value("PUBLISHED"));
                }
            }
        } finally {
            call(admin, put(STUDIO + "/settings"), Map.of("autoBlog", "OFF", "autoStory", "OFF", "autoNewsletter", false))
                    .andExpect(status().isOk());
        }
    }

    private long createPublished(MockHttpSession admin, String title, long priceMinor) throws Exception {
        Map<String, Object> in = new HashMap<>();
        in.put("productType", "DIGITAL");
        in.put("sellingMethod", "AFFILIATE");
        in.put("title", title);
        in.put("deliveryKind", "DOWNLOAD");
        in.put("summary", "A short video course that helps you build better habits.");
        in.put("highlights", "Deep work in 30 days\nLifetime access\nCertificate of completion");
        in.put("pros", "Clear, practical lessons\nShort videos");
        in.put("cons", "English only");
        JsonNode p = body(call(admin, post(ADMIN + "/products"), in).andExpect(status().isCreated()));
        long id = p.get("id").asLong();
        mvc.perform(multipart(ADMIN + "/products/" + id + "/images").file(new MockMultipartFile("file", "p.png", "image/png", pngBytes()))
                .session(admin).with(csrf())).andExpect(status().isOk());
        Map<String, Object> link = new HashMap<>();
        link.put("url", "https://www.udemy.com/course/" + p.get("slug").asText() + "/");
        link.put("displayPriceMinor", priceMinor);
        link.put("confirmNewDomain", true);
        call(admin, put(ADMIN + "/products/" + id + "/affiliate-link"), link).andExpect(status().isOk());
        call(admin, post(ADMIN + "/products/" + id + "/status"), Map.of("status", "PUBLISHED")).andExpect(status().isOk());
        return id;
    }

    private static byte[] pngBytes() throws Exception {
        BufferedImage img = new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, 600, 600);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static BufferedImage png(byte[] bytes) throws Exception {
        assertThat(bytes).startsWith(0x89, 'P', 'N', 'G');
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    private void createAdmin(String email, AdminRole role) {
        if (admins.findByStorefrontAndEmailIgnoreCase(Storefront.DIGITAL, email).isEmpty()) {
            admins.save(new AdminUser(Storefront.DIGITAL, email, passwords.encode(PASSWORD), "Studio", role, false));
        }
    }

    private MockHttpSession login(String email) throws Exception {
        MockHttpSession s = new MockHttpSession();
        call(s, post("/api/digital/admin/auth/login"), Map.of("email", email, "password", PASSWORD)).andExpect(status().isOk());
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
