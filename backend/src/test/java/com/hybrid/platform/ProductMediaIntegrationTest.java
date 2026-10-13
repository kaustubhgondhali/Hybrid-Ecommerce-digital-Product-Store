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
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Product videos (upload checks, poster, gallery data, Google video markup) and choosing the main image. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductMediaIntegrationTest {

    private static final String PASSWORD = "Media-Test-Passw0rd-1";
    private static final String ADMIN = "/api/shop/admin";

    /** The first bytes of an MP4 file ("ftyp" box, brand isom); the server checks content, not the file name. */
    private static final byte[] MP4 = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 2, 0,
            'i', 's', 'o', 'm', 'i', 's', 'o', '2', 0, 0, 0, 8, 'f', 'r', 'e', 'e'};

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
        if (admins.findByStorefrontAndEmailIgnoreCase(Storefront.SHOP, "media-master@test.local").isEmpty()) {
            admins.save(new AdminUser(Storefront.SHOP, "media-master@test.local", passwords.encode(PASSWORD), "Media",
                    AdminRole.MASTER_ADMIN, false));
        }
    }

    @Test
    void videosAndMainImage() throws Exception {
        MockHttpSession admin = login();
        JsonNode created = body(call(admin, post(ADMIN + "/products"), Map.of("productType", "PHYSICAL",
                "sellingMethod", "AFFILIATE", "title", "Media Test Smart Speaker")).andExpect(status().isCreated()));
        long id = created.get("id").asLong();
        upload(admin, id, "/images", png(Color.RED), "image/png").andExpect(status().isOk());
        JsonNode withTwo = body(upload(admin, id, "/images", png(Color.BLUE), "image/png").andExpect(status().isOk()));
        long second = withTwo.get("images").get(1).get("id").asLong();

        // ---- the second image becomes the main (first) image
        call(admin, post(ADMIN + "/products/" + id + "/images/" + second + "/main"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.images[0].id").value(second));

        // ---- a fake video is refused; a real MP4 with its poster frame is stored
        mvc.perform(multipart(ADMIN + "/products/" + id + "/videos")
                        .file(new MockMultipartFile("file", "clip.mp4", "video/mp4", "not really a video".getBytes()))
                        .session(admin).with(csrf()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_VIDEO"));
        JsonNode withVideo = body(mvc.perform(multipart(ADMIN + "/products/" + id + "/videos")
                        .file(new MockMultipartFile("file", "unboxing.mov", "video/quicktime", MP4))
                        .file(new MockMultipartFile("poster", "poster.jpg", "image/png", png(Color.GREEN)))
                        .param("title", "Unboxing and sound test").param("durationSeconds", "42")
                        .param("width", "1080").param("height", "1920")
                        .session(admin).with(csrf()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()));
        JsonNode video = withVideo.get("videos").get(0);
        assertThat(video.get("url").asText()).startsWith("/media/").endsWith(".mp4");
        assertThat(video.get("posterUrl").asText()).startsWith("/media/").endsWith(".png");
        assertThat(video.get("durationSeconds").asInt()).isEqualTo(42);
        assertThat(video.get("sizeBytes").asLong()).isEqualTo(MP4.length);
        long videoId = video.get("id").asLong();
        mvc.perform(get(video.get("url").asText())).andExpect(status().isOk());

        // nonsense measurements from the browser are ignored rather than stored
        JsonNode second2 = body(mvc.perform(multipart(ADMIN + "/products/" + id + "/videos")
                        .file(new MockMultipartFile("file", "demo.webm", "video/webm", new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 1, 2, 3, 4}))
                        .param("durationSeconds", "-5").param("width", "999999")
                        .session(admin).with(csrf()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()));
        assertThat(second2.get("videos").get(1).get("url").asText()).endsWith(".webm");
        assertThat(second2.get("videos").get(1).hasNonNull("durationSeconds")).isFalse();
        assertThat(second2.get("videos").get(1).hasNonNull("width")).isFalse();

        call(admin, patch(ADMIN + "/products/" + id + "/videos/" + videoId), Map.of("title", "Unboxing the speaker"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.videos[0].title").value("Unboxing the speaker"));

        // ---- shoppers get the videos with the product; Google gets VideoObject markup
        Map<String, Object> link = new HashMap<>();
        link.put("url", "https://www.croma.com/media-test-speaker/p/1");
        link.put("displayPriceMinor", 349900L);
        link.put("confirmNewDomain", true);
        call(admin, put(ADMIN + "/products/" + id + "/affiliate-link"), link).andExpect(status().isOk());
        call(admin, post(ADMIN + "/products/" + id + "/status"), Map.of("status", "PUBLISHED")).andExpect(status().isOk());
        String slug = created.get("slug").asText();
        call(new MockHttpSession(), get("/api/shop/catalog/products/" + slug), null)
                .andExpect(jsonPath("$.videos.length()").value(2))
                .andExpect(jsonPath("$.videos[0].title").value("Unboxing the speaker"))
                .andExpect(jsonPath("$.videos[0].contentType").value("video/mp4"))
                .andExpect(jsonPath("$.videos[1].contentType").value("video/webm"))
                .andExpect(jsonPath("$.videos[1].title").value("Media Test Smart Speaker"));
        String html = mvc.perform(get("/shop/p/" + slug)).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("\"@type\":\"VideoObject\"").contains("\"duration\":\"PT42S\"")
                .contains("\"name\":\"Unboxing the speaker\"");

        // ---- at most three videos; removing one frees a place
        mvc.perform(multipart(ADMIN + "/products/" + id + "/videos").file(new MockMultipartFile("file", "c.mp4", "video/mp4", MP4))
                .session(admin).with(csrf()).accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk());
        mvc.perform(multipart(ADMIN + "/products/" + id + "/videos").file(new MockMultipartFile("file", "d.mp4", "video/mp4", MP4))
                        .session(admin).with(csrf()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TOO_MANY_VIDEOS"));
        call(admin, delete(ADMIN + "/products/" + id + "/videos/" + videoId), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.videos.length()").value(2));
        mvc.perform(get(video.get("url").asText())).andExpect(status().isNotFound());
    }

    private ResultActions upload(MockHttpSession admin, long id, String path, byte[] bytes, String type) throws Exception {
        return mvc.perform(multipart(ADMIN + "/products/" + id + path).file(new MockMultipartFile("file", "f.png", type, bytes))
                .session(admin).with(csrf()).accept(MediaType.APPLICATION_JSON));
    }

    private static byte[] png(Color color) throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 8, 8);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private MockHttpSession login() throws Exception {
        MockHttpSession s = new MockHttpSession();
        call(s, post(ADMIN + "/auth/login"), Map.of("email", "media-master@test.local", "password", PASSWORD)).andExpect(status().isOk());
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
