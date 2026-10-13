package com.hybrid.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.crypto.CryptoService;
import com.hybrid.platform.crm.ConsentService;
import com.hybrid.platform.crm.CrmAutomationService;
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
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CRM: consent at sign-up, per-store customer data, segments, notes/tags/tasks, campaigns, reminders, unsubscribe. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CrmIntegrationTest {

    private static final String PASSWORD = "Crm-Test-Passw0rd-1";
    private static final String CRM = "/api/shop/admin/crm";

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
    @Autowired
    CrmAutomationService automations;
    @Autowired
    ConsentService consents;
    @Autowired
    CryptoService crypto;

    @BeforeEach
    void staff() {
        ensureAdmin("crm-master@test.local", AdminRole.MASTER_ADMIN);
        ensureAdmin("crm-support@test.local", AdminRole.SUPPORT);
        ensureAdmin("crm-catalog@test.local", AdminRole.CATALOG_MANAGER);
    }

    @Test
    void crmFromSignupToCampaignAndUnsubscribe() throws Exception {
        MockHttpSession master = login("crm-master@test.local");
        JsonNode product = body(call(master, post("/api/shop/admin/products"), Map.of("productType", "PHYSICAL",
                "sellingMethod", "DIRECT", "title", "CRM Test Lamp", "priceMinor", 99900, "sku", "CRM-LAMP-1"))
                .andExpect(status().isCreated()));
        long productId = product.get("id").asLong();
        long variantId = product.get("variants").get(0).get("id").asLong();

        // ---- sign-ups: only the ticked box subscribes, and only for the store the customer signed up on
        MockHttpSession asha = register("asha.crm@test.local", "Asha Rao", "shop", true);
        register("ravi.crm@test.local", "Ravi Kumar", "shop", false);
        register("dev.crm@test.local", "Dev Digital", "digital", true);
        long ashaId = customerId("asha.crm@test.local");
        long devId = customerId("dev.crm@test.local");
        call(asha, get("/api/marketing/preferences"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.shop").value(true)).andExpect(jsonPath("$.digital").value(false));

        // Asha saved two lamps three hours ago and did nothing since: an abandoned cart
        Instant saved = Instant.now().minus(Duration.ofHours(3));
        jdbc.update("insert into carts (customer_id, storefront, created_at, updated_at) values (?, 'SHOP', ?, ?)", ashaId, utc(saved), utc(saved));
        Long cartId = jdbc.queryForObject("select id from carts where customer_id = ? and storefront = 'SHOP'", Long.class, ashaId);
        jdbc.update("insert into cart_items (cart_id, product_id, variant_id, quantity, added_at) values (?, ?, ?, 2, ?)",
                cartId, productId, variantId, utc(saved));

        // ---- customer list is per storefront, with segments
        JsonNode list = body(call(master, get(CRM + "/customers?q=.crm@test.local&size=50"), null).andExpect(status().isOk()));
        assertThat(emails(list)).containsExactlyInAnyOrder("asha.crm@test.local", "ravi.crm@test.local");
        assertThat(emails(body(call(master, get(CRM + "/customers?segment=ABANDONED_CART&q=.crm@test.local"), null))))
                .containsExactly("asha.crm@test.local");
        assertThat(emails(body(call(master, get(CRM + "/customers?segment=NEW_SIGNUPS&q=.crm@test.local"), null))))
                .containsExactly("ravi.crm@test.local");
        call(master, get(CRM + "/customers?segment=NOPE"), null).andExpect(status().isBadRequest());

        JsonNode detail = body(call(master, get(CRM + "/customers/" + ashaId), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.subscribed").value(true))
                .andExpect(jsonPath("$.customer.cartItems").value(2))
                .andExpect(jsonPath("$.customer.nextActionCode").value("REMIND_CART")));
        List<String> events = new ArrayList<>();
        detail.get("timeline").forEach(e -> events.add(e.get("type").asText() + ":" + e.get("title").asText()));
        assertThat(events).contains("CART:CRM Test Lamp", "SIGNUP:Created an account");
        call(master, get(CRM + "/customers/" + devId), null).andExpect(status().isNotFound());
        call(master, get(CRM + "/overview"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].key").value("ALL"));

        // ---- support staff can work leads but not send marketing; catalog managers see no customer data
        MockHttpSession support = login("crm-support@test.local");
        call(support, post(CRM + "/customers/" + ashaId + "/notes"), Map.of("body", "Asked about bulk pricing"))
                .andExpect(status().isCreated());
        call(support, put(CRM + "/customers/" + ashaId + "/tags"), Map.of("tags", List.of("Bulk  Buyer", "vip ")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0]").value("bulk buyer")).andExpect(jsonPath("$[1]").value("vip"));
        call(support, put(CRM + "/customers/" + ashaId + "/tags"), Map.of("tags", List.of("<b>"))).andExpect(status().isBadRequest());
        assertThat(emails(body(call(support, get(CRM + "/customers?tag=vip"), null)))).containsExactly("asha.crm@test.local");
        JsonNode task = body(call(support, post(CRM + "/tasks"), Map.of("customerId", ashaId, "title", "Call about the lamps",
                "dueOn", LocalDate.now().plusDays(1).toString())).andExpect(status().isCreated()));
        call(support, patch(CRM + "/tasks/" + task.get("id").asLong()), Map.of("done", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.done").value(true));
        call(support, post(CRM + "/tasks"), Map.of("customerId", devId, "title", "Not my store")).andExpect(status().isNotFound());
        call(support, post(CRM + "/campaigns"), Map.of("name", "x", "subject", "x", "body", "x")).andExpect(status().isForbidden());
        call(support, put(CRM + "/automations"), Map.of("cartReminderEnabled", true)).andExpect(status().isForbidden());
        call(login("crm-catalog@test.local"), get(CRM + "/overview"), null).andExpect(status().isForbidden());

        JsonNode campaign = body(call(master, post(CRM + "/campaigns"), Map.of("name", "Lamp week", "segment", "ALL",
                "subject", "Hi {name}, lamp week at {store}", "body", "Hello {name}! Fresh deals: {link}")).andExpect(status().isCreated()));
        long campaignId = campaign.get("id").asLong();
        call(master, post(CRM + "/campaigns/" + campaignId + "/send"), null)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SMTP_NOT_READY"));

        // SMTP pointing at a closed port: emails are queued and logged (delivery fails, which is fine here)
        call(master, put("/api/shop/admin/settings/smtp"), Map.of("enabled", true, "host", "localhost", "port", 1,
                "security", "NONE", "fromEmail", "shop@test.local")).andExpect(status().isOk());
        try {
            // ---- cart reminder: once, to the subscribed customer only
            call(master, put(CRM + "/automations"), Map.of("cartReminderEnabled", true, "cartReminderHours", 1))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.cartReminderEnabled").value(true));
            automations.runDue(Storefront.SHOP, Instant.now());
            automations.runDue(Storefront.SHOP, Instant.now());
            assertThat(mails("CRM_CART_REMINDER", "asha.crm@test.local")).isEqualTo(1);

            // ---- test send goes to staff only, with a sample name
            call(master, post(CRM + "/campaigns/" + campaignId + "/test"), Map.of("to", "crm-master@test.local"))
                    .andExpect(status().isAccepted());
            assertThat(jdbc.queryForObject("select subject from notification_log where kind = 'CAMPAIGN_TEST' and recipient = ?",
                    String.class, "crm-master@test.local")).startsWith("[Test] Hi Asha, lamp week at ");

            // ---- campaign: only subscribers, merge fields filled, one-click unsubscribe link in every email
            call(master, post(CRM + "/campaigns/" + campaignId + "/send"), null).andExpect(status().isOk());
            waitFor(() -> "SENT".equals(jdbc.queryForObject("select status from crm_campaigns where id = ?", String.class, campaignId)));
            assertThat(mails("CAMPAIGN", "asha.crm@test.local")).isEqualTo(1);
            assertThat(mails("CAMPAIGN", "ravi.crm@test.local")).isZero();
            assertThat(mails("CAMPAIGN", "dev.crm@test.local")).isZero();
            Map<String, Object> mail = jdbc.queryForMap("select subject, body from notification_log where kind = 'CAMPAIGN' and recipient = ?",
                    "asha.crm@test.local");
            assertThat((String) mail.get("subject")).startsWith("Hi Asha, lamp week at ");
            String sentBody = crypto.decrypt((String) mail.get("body"));
            assertThat(sentBody).startsWith("Hello Asha! Fresh deals: http://localhost/shop")
                    .contains("/account/unsubscribe.html?c=" + ashaId + "&sf=shop&t=");
            call(master, post(CRM + "/campaigns/" + campaignId + "/send"), null).andExpect(status().isConflict());

            // ---- results: what recipients did in the 7 days after sending
            jdbc.update("insert into affiliate_clicks (click_ref, link_id, storefront, product_id, customer_id, suspected_bot, created_at) "
                    + "values ('crm-test-click', 0, 'SHOP', ?, ?, false, ?)", productId, ashaId, utc(Instant.now()));
            call(master, get(CRM + "/campaigns/" + campaignId), null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SENT"))
                    .andExpect(jsonPath("$.results.clicks").value(1))
                    .andExpect(jsonPath("$.results.clickers").value(1));
        } finally {
            call(master, put(CRM + "/automations"), Map.of("cartReminderEnabled", false)).andExpect(status().isOk());
            call(master, put("/api/shop/admin/settings/smtp"), Map.of("enabled", false)).andExpect(status().isOk());
        }

        // ---- unsubscribe works without signing in, but only with the signed link
        Map<String, String> q = UriComponentsBuilder.fromUriString(consents.unsubscribeUrl(ashaId, Storefront.SHOP)).build()
                .getQueryParams().toSingleValueMap();
        call(new MockHttpSession(), post("/api/marketing/unsubscribe"), Map.of("c", ashaId, "s", "shop", "t", "forged"))
                .andExpect(status().isBadRequest());
        call(new MockHttpSession(), post("/api/marketing/unsubscribe"), Map.of("c", devId, "s", "shop", "t", q.get("t")))
                .andExpect(status().isBadRequest());
        call(new MockHttpSession(), post("/api/marketing/unsubscribe"), Map.of("c", ashaId, "s", "shop", "t", q.get("t")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unsubscribed").value(true));
        call(asha, get("/api/marketing/preferences"), null).andExpect(jsonPath("$.shop").value(false));
        call(asha, put("/api/marketing/preferences"), Map.of("shop", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.shop").value(true));
        call(new MockHttpSession(), get("/api/marketing/preferences"), null).andExpect(status().isUnauthorized());
    }

    private MockHttpSession register(String email, String name, String storefront, boolean optIn) throws Exception {
        MockHttpSession s = new MockHttpSession();
        call(s, post("/api/auth/register"), Map.of("email", email, "password", "Customer-Passw0rd1", "fullName", name,
                "storefront", storefront, "marketingOptIn", optIn)).andExpect(status().isCreated());
        return s;
    }

    private long customerId(String email) {
        Long id = jdbc.queryForObject("select id from customers where email = ?", Long.class, email);
        return id == null ? -1 : id;
    }

    private int mails(String kind, String recipient) {
        Integer n = jdbc.queryForObject("select count(*) from notification_log where kind = ? and recipient = ?", Integer.class, kind, recipient);
        return n == null ? 0 : n;
    }

    private static List<String> emails(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("email").asText()));
        return out;
    }

    private static LocalDateTime utc(Instant i) {
        return LocalDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) Thread.sleep(100);
        assertThat(condition.getAsBoolean()).as("condition within 10 seconds").isTrue();
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
}
