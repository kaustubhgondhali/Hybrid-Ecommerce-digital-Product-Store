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
import org.springframework.mock.web.MockHttpServletResponse;
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

/** Web Stories: building, publishing rules, the AMP page Google reads, previews, sitemap, storefront list, permissions. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebStoriesIntegrationTest {

    private static final String PASSWORD = "Stories-Test-Passw0rd-1";
    private static final String ADMIN = "/api/digital/admin";
    private static final byte[] MP4 = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2', 0, 0, 0, 0,
            'm', 'p', '4', '2', 'i', 's', 'o', 'm', 0, 0, 0, 8, 'f', 'r', 'e', 'e'};

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
        createAdmin(Storefront.DIGITAL, "stories-master@test.local", AdminRole.MASTER_ADMIN);
        createAdmin(Storefront.DIGITAL, "stories-support@test.local", AdminRole.SUPPORT);
        createAdmin(Storefront.SHOP, "stories-shop@test.local", AdminRole.MASTER_ADMIN);
    }

    @Test
    void buildPublishAndServeStories() throws Exception {
        MockHttpSession admin = login("digital", "stories-master@test.local");

        // ---- a blank story: cover (landscape photo becomes a 720 x 960 portrait) and pages
        JsonNode story = body(call(admin, post(ADMIN + "/stories"), Map.of("title", "Stories Test: Best note apps"))
                .andExpect(status().isCreated()));
        long id = story.get("id").asLong();
        assertThat(story.get("slug").asText()).isEqualTo("stories-test-best-note-apps");
        assertThat(story.get("status").asText()).isEqualTo("DRAFT");
        assertThat(story.get("problems").toString()).contains("add a cover photo").contains("add at least 3 pages");
        call(admin, post(ADMIN + "/stories/" + id + "/publish"), null)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("STORY_NOT_READY"));

        JsonNode withCover = body(mvc.perform(multipart(ADMIN + "/stories/" + id + "/cover")
                        .file(new MockMultipartFile("file", "c.png", "image/png", png(1200, 800, Color.ORANGE)))
                        .session(admin).with(csrf()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()));
        String cover = withCover.get("coverUrl").asText();
        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(
                mvc.perform(get(cover)).andReturn().getResponse().getContentAsByteArray()));
        assertThat(stored.getWidth()).isEqualTo(720);
        assertThat(stored.getHeight()).isEqualTo(960);

        long product = createPublished(admin, "Stories Test Notes Pro", "https://www.udemy.com/course/stories-notes/");
        addPhoto(admin, id, png(1080, 1920, Color.BLUE), Map.of("headline", "Write anywhere", "body", "Syncs across phone and laptop."));
        addPhoto(admin, id, png(800, 800, Color.GREEN), Map.of("headline", "Notes Pro", "productId", String.valueOf(product), "ctaLabel", "Get the deal"));
        // a video page needs its poster frame; without it the server asks for an MP4 the browser can read
        mvc.perform(multipart(ADMIN + "/stories/" + id + "/pages").file(new MockMultipartFile("file", "v.mp4", "video/mp4", MP4))
                        .session(admin).with(csrf()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("POSTER_REQUIRED"));
        mvc.perform(multipart(ADMIN + "/stories/" + id + "/pages").file(new MockMultipartFile("file", "v.mp4", "video/mp4", MP4))
                        .file(new MockMultipartFile("poster", "p.jpg", "image/png", png(540, 960, Color.RED)))
                        .param("durationSeconds", "95")
                        .session(admin).with(csrf()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VIDEO_TOO_LONG"));
        JsonNode three = body(mvc.perform(multipart(ADMIN + "/stories/" + id + "/pages").file(new MockMultipartFile("file", "v.mp4", "video/mp4", MP4))
                        .file(new MockMultipartFile("poster", "p.jpg", "image/png", png(540, 960, Color.RED)))
                        .param("durationSeconds", "12").param("headline", "See it in action")
                        .session(admin).with(csrf()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()));
        assertThat(three.get("pages").size()).isEqualTo(3);
        assertThat(three.get("pages").get(1).get("productTitle").asText()).isEqualTo("Stories Test Notes Pro");
        assertThat(three.get("problems").size()).isZero();

        // move the video to the front, then back
        long videoPage = three.get("pages").get(2).get("id").asLong();
        call(admin, post(ADMIN + "/stories/" + id + "/pages/" + videoPage + "/move").param("direction", "-1"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.pages[1].mediaType").value("VIDEO"));
        call(admin, post(ADMIN + "/stories/" + id + "/pages/" + videoPage + "/move").param("direction", "1"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.pages[2].mediaType").value("VIDEO"));

        // ---- drafts: preview only for this store's staff, never for the public or the other store's admin
        mvc.perform(get("/digital/stories/stories-test-best-note-apps")).andExpect(status().isNotFound());
        mvc.perform(get("/digital/stories/stories-test-best-note-apps").session(login("shop", "stories-shop@test.local")))
                .andExpect(status().isNotFound());
        String preview = mvc.perform(get("/digital/stories/stories-test-best-note-apps").session(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(preview).contains("noindex");

        // ---- publish: a valid-looking AMP Web Story with the right metadata, CSP and live product price
        call(admin, post(ADMIN + "/stories/" + id + "/publish"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
        MockHttpServletResponse page = mvc.perform(get("/digital/stories/stories-test-best-note-apps")).andExpect(status().isOk())
                .andReturn().getResponse();
        String html = page.getContentAsString();
        assertThat(html).startsWith("<!doctype html>").contains("<html amp lang=\"en\">")
                .contains("<style amp-boilerplate>").contains("https://cdn.ampproject.org/v0/amp-story-1.0.js")
                .contains("custom-element=\"amp-video\"")
                .contains("<amp-story standalone title=\"Stories Test: Best note apps\"")
                .contains("poster-portrait-src=\"http://localhost").contains("publisher-logo-src=\"http://localhost")
                .contains("elaatech-logo-square.png")
                .contains("<link rel=\"canonical\" href=\"http://localhost")
                .contains("index, follow, max-image-preview:large")
                .contains("Write anywhere").contains("auto-advance-after=\"page-3-video\"")
                .contains("<amp-story-page-outlink layout=\"nodisplay\"").contains(">Get the deal</a>")
                .contains("/digital/p/stories-test-notes-pro")
                .contains("₹999 at udemy.com")
                .contains("\"@type\":\"Article\"")
                .doesNotContain("<script>").doesNotContain("udemy.com/course/stories-notes");
        assertThat(page.getHeader("Content-Security-Policy")).contains("script-src https://cdn.ampproject.org");
        assertThat(page.getHeader("Cache-Control")).contains("max-age=300");

        // ---- the storefront strip, the /stories page and the sitemap list it
        call(new MockHttpSession(), get("/api/digital/catalog/stories"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Stories Test: Best note apps"))
                .andExpect(jsonPath("$[0].url").value("/digital/stories/stories-test-best-note-apps"))
                .andExpect(jsonPath("$[0].pages").value(5));
        call(new MockHttpSession(), get("/api/shop/catalog/stories"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.slug == 'stories-test-best-note-apps')]").isEmpty());
        String index = mvc.perform(get("/digital/stories")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(index).contains("Web Stories").contains("href=\"/digital/stories/stories-test-best-note-apps\"");
        String sitemap = mvc.perform(get("/sitemap.xml")).andReturn().getResponse().getContentAsString();
        assertThat(sitemap).contains("/digital/stories/stories-test-best-note-apps</loc>").contains("/digital/stories</loc>");
        String home = mvc.perform(get("/digital")).andReturn().getResponse().getContentAsString();
        assertThat(home).contains("href=\"/digital/stories/stories-test-best-note-apps\"");

        // ---- renaming a published story keeps the old link working
        call(admin, put(ADMIN + "/stories/" + id), Map.of("slug", "note-apps-we-love")).andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("note-apps-we-love"));
        mvc.perform(get("/digital/stories/stories-test-best-note-apps")).andExpect(status().isMovedPermanently());

        // ---- one click from products (3 to 10, published, with a photo)
        long p2 = createPublished(admin, "Stories Test Mind Maps", "https://www.udemy.com/course/stories-maps/");
        long p3 = createPublished(admin, "Stories Test Task Board", "https://www.udemy.com/course/stories-tasks/");
        call(admin, post(ADMIN + "/stories/from-products"), Map.of("title", "Too few", "productIds", List.of(product, p2)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CHOOSE_PRODUCTS"));
        JsonNode generated = body(call(admin, post(ADMIN + "/stories/from-products"),
                Map.of("title", "Stories Test: 3 productivity picks", "productIds", List.of(product, p2, p3), "publish", true))
                .andExpect(status().isCreated()));
        assertThat(generated.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(generated.get("pages").size()).isEqualTo(3);
        assertThat(generated.get("coverUrl").asText()).startsWith("/media/");
        assertThat(generated.get("pages").get(0).get("headline").asText()).isEqualTo("Stories Test Notes Pro");
        assertThat(generated.get("pages").get(0).get("productId").asLong()).isEqualTo(product);
        assertThat(generated.get("description").asText()).startsWith("Hand-picked: Stories Test Notes Pro");

        // ---- permissions: support staff can look but not change; another store's admin cannot see it at all
        MockHttpSession support = login("digital", "stories-support@test.local");
        call(support, get(ADMIN + "/stories"), null).andExpect(status().isOk());
        call(support, post(ADMIN + "/stories"), Map.of("title", "Nope")).andExpect(status().isForbidden());
        call(login("shop", "stories-shop@test.local"), get("/api/shop/admin/stories/" + id), null).andExpect(status().isNotFound());
        call(new MockHttpSession(), get(ADMIN + "/stories"), null).andExpect(status().isUnauthorized());

        // ---- deleting removes the story, its files and its page
        call(admin, delete(ADMIN + "/stories/" + id), null).andExpect(status().isNoContent());
        mvc.perform(get(cover)).andExpect(status().isNotFound());
        mvc.perform(get("/digital/stories/note-apps-we-love")).andExpect(status().isNotFound());
    }

    private void addPhoto(MockHttpSession admin, long id, byte[] png, Map<String, String> params) throws Exception {
        var req = multipart(ADMIN + "/stories/" + id + "/pages").file(new MockMultipartFile("file", "p.png", "image/png", png));
        params.forEach(req::param);
        mvc.perform(req.session(admin).with(csrf()).accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk());
    }

    private long createPublished(MockHttpSession admin, String title, String url) throws Exception {
        JsonNode p = body(call(admin, post(ADMIN + "/products"), Map.of("productType", "DIGITAL", "sellingMethod", "AFFILIATE",
                "title", title, "deliveryKind", "DOWNLOAD")).andExpect(status().isCreated()));
        long id = p.get("id").asLong();
        mvc.perform(multipart(ADMIN + "/products/" + id + "/images").file(new MockMultipartFile("file", "p.png", "image/png", png(600, 600, Color.GRAY)))
                .session(admin).with(csrf())).andExpect(status().isOk());
        Map<String, Object> link = new HashMap<>();
        link.put("url", url);
        link.put("displayPriceMinor", 99900L);
        link.put("confirmNewDomain", true);
        call(admin, put(ADMIN + "/products/" + id + "/affiliate-link"), link).andExpect(status().isOk());
        call(admin, post(ADMIN + "/products/" + id + "/status"), Map.of("status", "PUBLISHED")).andExpect(status().isOk());
        return id;
    }

    private static byte[] png(int w, int h, Color color) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private void createAdmin(Storefront sf, String email, AdminRole role) {
        if (admins.findByStorefrontAndEmailIgnoreCase(sf, email).isEmpty()) {
            admins.save(new AdminUser(sf, email, passwords.encode(PASSWORD), "Stories", role, false));
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
