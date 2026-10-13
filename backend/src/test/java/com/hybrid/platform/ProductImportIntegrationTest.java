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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** CSV bulk import of affiliate products: check first, approve new stores, drafts with links, bad rows reported. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductImportIntegrationTest {

    private static final String PASSWORD = "Import-Test-Passw0rd-1";
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
        for (var who : List.of(Map.entry("import-master@test.local", AdminRole.MASTER_ADMIN), Map.entry("import-orders@test.local", AdminRole.ORDER_MANAGER))) {
            if (admins.findByStorefrontAndEmailIgnoreCase(Storefront.DIGITAL, who.getKey()).isEmpty()) {
                admins.save(new AdminUser(Storefront.DIGITAL, who.getKey(), passwords.encode(PASSWORD), "Import", who.getValue(), false));
            }
        }
        if (jdbc.queryForObject("select count(*) from categories where storefront = 'DIGITAL' and slug = 'import-test-courses'", Integer.class) == 0) {
            jdbc.update("insert into categories (storefront, name, slug, sort_order, active) values ('DIGITAL', 'Import Test Courses', 'import-test-courses', 99, true)");
        }
    }

    @Test
    void checkThenImportDrafts() throws Exception {
        MockHttpSession admin = login("import-master@test.local");
        String csv = "﻿Product Name;Affiliate Link;Price (₹);MRP;Category;Store;Highlights\n"
                + "Import Test Rust Course;https://www.learnhub-import.com/rust?ref=me;₹1,299;\"4,999\";Import Test Courses;LearnHub;Projects | Certificate\n"
                + "\"Import Test Excel; Sheets & \"\"Data\"\" Course\";https://www.learnhub-import.com/excel;499;;import-test-courses;;\n"
                + "Import Test Rust Course;https://www.learnhub-import.com/rust-again;999;;;;\n"
                + "Import Test No Link;;499;;;;\n"
                + "Import Test Bad Category;https://www.learnhub-import.com/x;499;;Cooking;;\n"
                + "Import Test Bad Price;https://www.learnhub-import.com/y;abc;;;;\n"
                + "Import Test Cheap MRP;https://www.learnhub-import.com/z;900;500;;;\n";

        // ---- check only: nothing saved, new store listed, every problem explained
        JsonNode check = run(admin, csv, true, false);
        assertThat(check.get("dryRun").asBoolean()).isTrue();
        assertThat(check.get("total").asInt()).isEqualTo(7);
        assertThat(check.get("newStores").toString()).contains("learnhub-import.com");
        Map<Integer, JsonNode> rows = byRow(check);
        assertThat(rows.get(2).get("status").asText()).isEqualTo("NEW_STORE");
        assertThat(rows.get(2).get("priceMinor").asLong()).isEqualTo(129900);
        assertThat(rows.get(2).get("mrpMinor").asLong()).isEqualTo(499900);
        assertThat(rows.get(3).get("title").asText()).isEqualTo("Import Test Excel; Sheets & \"Data\" Course");
        assertThat(rows.get(3).get("category").asText()).isEqualTo("Import Test Courses");
        assertThat(rows.get(4).get("message").asText()).contains("appears twice");
        assertThat(rows.get(5).get("message").asText()).contains("link is missing");
        assertThat(rows.get(6).get("message").asText()).contains("No category called");
        assertThat(rows.get(7).get("message").asText()).contains("not a valid amount");
        assertThat(rows.get(8).get("message").asText()).contains("MRP must be higher");
        assertThat(check.get("failed").asInt()).isEqualTo(5);
        assertThat(count("Import Test Rust Course")).isZero();

        // ---- without approving the new store nothing is created; with approval two drafts are
        assertThat(run(admin, csv, false, false).get("created").asInt()).isZero();
        JsonNode done = run(admin, csv, false, true);
        assertThat(done.get("created").asInt()).isEqualTo(2);
        long id = byRow(done).get(2).get("productId").asLong();
        JsonNode product = body(call(admin, get(ADMIN + "/products/" + id), null));
        assertThat(product.get("status").asText()).isEqualTo("DRAFT");
        assertThat(product.get("highlights").toString()).contains("Projects").contains("Certificate");
        assertThat(jdbc.queryForObject("select display_price_minor from affiliate_links where product_id = ?", Long.class, id)).isEqualTo(129900L);
        assertThat(jdbc.queryForObject("select count(*) from affiliate_merchants where storefront = 'DIGITAL' and domain = 'learnhub-import.com'", Integer.class)).isEqualTo(1);

        // ---- importing the same file again skips what is already there
        JsonNode again = run(admin, csv, true, false);
        assertThat(byRow(again).get(2).get("message").asText()).contains("Already in your catalog");

        // ---- wrong header, empty file, missing permission
        call(admin, post(ADMIN + "/products/import"), Map.of("csv", "name,price\nA,1\n")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_COLUMNS_MISSING"));
        call(admin, post(ADMIN + "/products/import"), Map.of("csv", "")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_EMPTY"));
        call(login("import-orders@test.local"), post(ADMIN + "/products/import"), Map.of("csv", csv)).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode run(MockHttpSession admin, String csv, boolean dryRun, boolean approve) throws Exception {
        Map<String, Object> in = new HashMap<>();
        in.put("csv", csv);
        in.put("dryRun", dryRun);
        in.put("approveNewStores", approve);
        return body(call(admin, post(ADMIN + "/products/import"), in).andExpect(status().isOk()));
    }

    private static Map<Integer, JsonNode> byRow(JsonNode report) {
        Map<Integer, JsonNode> out = new HashMap<>();
        report.get("rows").forEach(r -> out.put(r.get("row").asInt(), r));
        return out;
    }

    private int count(String title) {
        return jdbc.queryForObject("select count(*) from products where storefront = 'DIGITAL' and title = ?", Integer.class, title);
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
