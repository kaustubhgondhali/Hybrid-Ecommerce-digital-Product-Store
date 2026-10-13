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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Shopper features: sorting and filters on live offers, search suggestions, stores, specifications, click counts, compare. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShoppingFeaturesIntegrationTest {

    private static final String PASSWORD = "Shopping-Test-Passw0rd-1";
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
        if (admins.findByStorefrontAndEmailIgnoreCase(Storefront.DIGITAL, "shopping-master@test.local").isEmpty()) {
            admins.save(new AdminUser(Storefront.DIGITAL, "shopping-master@test.local", passwords.encode(PASSWORD), "Shopping",
                    AdminRole.MASTER_ADMIN, false));
        }
    }

    @Test
    void sortFilterSuggestCompare() throws Exception {
        MockHttpSession admin = login();
        long cheap = createPublished(admin, "Zebrafish Photo Course", "https://www.udemy.com/course/zebrafish-photo/", 49900L, 59900L);
        long big = createPublished(admin, "Zebrafish Video Masterclass", "https://www.coursera.org/learn/zebrafish-video", 99900L, 199900L);
        long pricey = createPublished(admin, "Zebrafish Studio Suite", "https://www.udemy.com/course/zebrafish-studio/", 249900L, null);

        // ---- sorts on the live best offer
        assertThat(ids(browse("q", "zebrafish", "sort", "price_asc"))).containsExactly(cheap, big, pricey);
        assertThat(ids(browse("q", "zebrafish", "sort", "price_desc"))).containsExactly(pricey, big, cheap);
        assertThat(ids(browse("q", "zebrafish", "sort", "discount"))).containsSubsequence(big, cheap);
        assertThat(ids(browse("q", "zebrafish", "sort", "discount")).get(0)).isEqualTo(big);

        // ---- filters: store (any of the product's stores), minimum discount, price range (rupees)
        assertThat(ids(browse("q", "zebrafish", "store", "udemy.com"))).containsExactlyInAnyOrder(cheap, pricey);
        assertThat(ids(browse("q", "zebrafish", "minDiscount", "30"))).containsExactly(big);
        assertThat(ids(browse("q", "zebrafish", "minPrice", "500", "maxPrice", "1000"))).containsExactly(big);
        JsonNode paged = browse("q", "zebrafish", "sort", "price_asc", "size", "2", "page", "1");
        assertThat(paged.get("totalItems").asLong()).isEqualTo(3);
        assertThat(paged.get("totalPages").asInt()).isEqualTo(2);
        assertThat(ids(paged)).containsExactly(pricey);

        // ---- popular: real (non-bot) clicks; the product page shows the 7-day count
        String goUrl = card(browse("q", "zebrafish studio"), pricey).get("goUrl").asText();
        for (int i = 0; i < 6; i++) {
            mvc.perform(get(goUrl).header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0"))
                    .andExpect(status().is3xxRedirection());
        }
        assertThat(ids(browse("q", "zebrafish", "sort", "popular")).get(0)).isEqualTo(pricey);
        String studioSlug = card(browse("q", "zebrafish studio"), pricey).get("slug").asText();
        call(new MockHttpSession(), get("/api/digital/catalog/products/" + studioSlug), null)
                .andExpect(jsonPath("$.clicks7d").value(6));

        // ---- search suggestions match part of a word; categories and products are returned separately
        JsonNode suggest = body(call(new MockHttpSession(), get("/api/digital/catalog/suggest").param("q", "zebraf"), null)
                .andExpect(status().isOk()));
        assertThat(suggest.get("products").size()).isEqualTo(3);
        assertThat(body(call(new MockHttpSession(), get("/api/digital/catalog/suggest").param("q", "z"), null)).get("products").size()).isZero();

        // ---- cards by id (recently viewed / compare), stores with deals
        JsonNode cards = body(call(new MockHttpSession(), get("/api/digital/catalog/cards").param("ids", big + "," + cheap + ",999999"), null));
        assertThat(cards.size()).isEqualTo(2);
        assertThat(cards.get(0).get("id").asLong()).isEqualTo(big);
        JsonNode stores = body(call(new MockHttpSession(), get("/api/digital/catalog/stores"), null));
        List<String> domains = new ArrayList<>();
        stores.forEach(s -> domains.add(s.get("domain").asText()));
        assertThat(domains).contains("udemy.com", "coursera.org");

        // ---- specifications: "Name: Value" lines, shown on the product and checked on save
        Map<String, Object> specs = new HashMap<>();
        specs.put("specs", "- Platform: Web, iOS\nFree trial: 14 days\n\nCertificate: Yes");
        specs.put("version", body(call(admin, get(ADMIN + "/products/" + big), null)).get("version").asLong());
        call(admin, put(ADMIN + "/products/" + big), specs).andExpect(status().isOk())
                .andExpect(jsonPath("$.specs").value("Platform: Web, iOS\nFree trial: 14 days\nCertificate: Yes"));
        String bigSlug = card(browse("q", "zebrafish video"), big).get("slug").asText();
        call(new MockHttpSession(), get("/api/digital/catalog/products/" + bigSlug), null)
                .andExpect(jsonPath("$.specs.length()").value(3))
                .andExpect(jsonPath("$.specs[1].name").value("Free trial"))
                .andExpect(jsonPath("$.specs[1].value").value("14 days"));
        Map<String, Object> bad = new HashMap<>();
        bad.put("specs", "Just a sentence without a name");
        bad.put("version", body(call(admin, get(ADMIN + "/products/" + big), null)).get("version").asLong());
        call(admin, put(ADMIN + "/products/" + big), bad).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SPEC"));

        // ---- the compare page exists for both storefronts and is not indexed
        mvc.perform(get("/digital/compare")).andExpect(status().isOk()).andExpect(forwardedUrl("/store/compare.html"));
        String compare = mvc.perform(get("/store/compare.html")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(compare).contains("data-page=\"store-compare\"").contains("noindex");
    }

    private JsonNode browse(String... params) throws Exception {
        var req = get("/api/digital/catalog/products");
        for (int i = 0; i < params.length; i += 2) req.param(params[i], params[i + 1]);
        return body(call(new MockHttpSession(), req, null).andExpect(status().isOk()));
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> out = new ArrayList<>();
        page.get("items").forEach(c -> out.add(c.get("id").asLong()));
        return out;
    }

    private static JsonNode card(JsonNode page, long id) {
        for (JsonNode n : page.get("items")) if (n.get("id").asLong() == id) return n;
        throw new AssertionError("product " + id + " not listed in " + page);
    }

    private long createPublished(MockHttpSession admin, String title, String url, Long price, Long listPrice) throws Exception {
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
        link.put("listPriceMinor", listPrice);
        link.put("confirmNewDomain", true);
        call(admin, put(ADMIN + "/products/" + id + "/affiliate-link"), link).andExpect(status().isOk());
        call(admin, post(ADMIN + "/products/" + id + "/status"), Map.of("status", "PUBLISHED")).andExpect(status().isOk());
        return id;
    }

    private MockHttpSession login() throws Exception {
        MockHttpSession s = new MockHttpSession();
        call(s, post(ADMIN + "/auth/login"), Map.of("email", "shopping-master@test.local", "password", PASSWORD)).andExpect(status().isOk());
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
