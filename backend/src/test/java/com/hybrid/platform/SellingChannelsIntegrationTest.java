package com.hybrid.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.identity.AdminRole;
import com.hybrid.platform.identity.AdminUser;
import com.hybrid.platform.identity.AdminUserRepository;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Own-stock, dropship and affiliate products side by side, and the per-storefront checkout switch. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SellingChannelsIntegrationTest {

    private static final String PASSWORD = "Channels-Passw0rd-1";

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

    @Test
    void dropshipAndPartnerProductsShareOneCart() throws Exception {
        MockHttpSession admin = master();

        call(admin, post("/api/shop/admin/products"), Map.of("productType", "DIGITAL", "sellingMethod", "DROPSHIP",
                "title", "Dropship ebook")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DROPSHIP_PHYSICAL_ONLY"));

        JsonNode lamp = body(call(admin, post("/api/shop/admin/products"), Map.of(
                "productType", "PHYSICAL", "sellingMethod", "DROPSHIP", "title", "Supplier Desk Lamp",
                "priceMinor", 129900, "sku", "DS-LAMP-1", "weightGrams", 900)).andExpect(status().isCreated()));
        long lampId = lamp.get("id").asLong();
        long lampVariant = lamp.get("variants").get(0).get("id").asLong();
        uploadImage(admin, "/api/shop/admin/products/" + lampId + "/images");
        call(admin, post("/api/shop/admin/products/" + lampId + "/status"), Map.of("status", "PUBLISHED"))
                .andExpect(status().isOk());
        Integer stockRows = jdbc.queryForObject("select count(*) from inventory_items where variant_id = ?", Integer.class, lampVariant);
        assertThat(stockRows).isZero();

        JsonNode partner = body(call(admin, post("/api/shop/admin/products"), Map.of(
                "productType", "PHYSICAL", "sellingMethod", "AFFILIATE", "title", "Partner Office Chair"))
                .andExpect(status().isCreated()));
        long partnerId = partner.get("id").asLong();
        long partnerVariant = partner.get("variants").get(0).get("id").asLong();
        uploadImage(admin, "/api/shop/admin/products/" + partnerId + "/images");
        call(admin, put("/api/shop/admin/products/" + partnerId + "/affiliate-link"),
                Map.of("url", "https://www.channels-partner.example/chair?ref=test", "confirmNewDomain", true,
                        "displayPriceMinor", 899900)).andExpect(status().isOk());
        call(admin, post("/api/shop/admin/products/" + partnerId + "/status"), Map.of("status", "PUBLISHED"))
                .andExpect(status().isOk());

        MockHttpSession buyer = new MockHttpSession();
        call(buyer, post("/api/auth/register"), Map.of("email", "channels-buyer@test.local", "password", "Buyer-Passw0rd1",
                "fullName", "Channel Buyer", "storefront", "shop")).andExpect(status().isCreated());

        // No own inventory needed: the supplier ships it.
        call(buyer, post("/api/shop/cart/items"), Map.of("variantId", lampVariant, "quantity", 3)).andExpect(status().isOk());
        call(buyer, post("/api/shop/cart/items"), Map.of("variantId", partnerVariant)).andExpect(status().isOk())
                .andExpect(jsonPath("$.hasDirectItems").value(true))
                .andExpect(jsonPath("$.hasAffiliateItems").value(true))
                .andExpect(jsonPath("$.requiresShipping").value(true))
                .andExpect(jsonPath("$.checkoutSubtotalMinor").value(3 * 129900));

        JsonNode quote = body(call(buyer, post("/api/shop/checkout/quote"), Map.of()).andExpect(status().isOk()));
        assertThat(quote.get("lines")).hasSize(1);
        assertThat(quote.get("problems")).isEmpty();
        assertThat(quote.get("subtotalMinor").asLong()).isEqualTo(3 * 129900);

        Map<String, Object> checkout = Map.of("shippingMethodId", quote.get("shippingMethodId").asLong(),
                "address", Map.of("name", "Channel Buyer", "phone", "+91 98765 43210", "line1", "2 Supplier Road",
                        "city", "Pune", "state", "Maharashtra", "postalCode", "411001", "country", "IN"));
        JsonNode placed = body(call(buyer, post("/api/shop/checkout/orders").header("Idempotency-Key", "channels-key-0001"), checkout)
                .andExpect(status().isCreated()));
        String orderNumber = placed.get("orderNumber").asText();
        call(buyer, post("/api/payments/demo/" + orderNumber + "/complete"), Map.of("outcome", "SUCCESS"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));

        List<String> methods = jdbc.queryForList("select l.selling_method from order_lines l join orders o on o.id = l.order_id"
                + " where o.order_number = ?", String.class, orderNumber);
        assertThat(methods).containsExactly("DROPSHIP");

        // The partner item stays in the cart; only the checked-out item is removed.
        call(buyer, get("/api/shop/cart"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].variantId").value(partnerVariant));
    }

    @Test
    void checkoutCanBeSwitchedOffPerStorefront() throws Exception {
        MockHttpSession admin = master();
        JsonNode kettle = body(call(admin, post("/api/shop/admin/products"), Map.of(
                "productType", "PHYSICAL", "sellingMethod", "DROPSHIP", "title", "Switch Test Kettle",
                "priceMinor", 59900, "sku", "DS-KETTLE-1", "weightGrams", 700)).andExpect(status().isCreated()));
        long kettleId = kettle.get("id").asLong();
        uploadImage(admin, "/api/shop/admin/products/" + kettleId + "/images");
        call(admin, post("/api/shop/admin/products/" + kettleId + "/status"), Map.of("status", "PUBLISHED"))
                .andExpect(status().isOk());
        String slug = kettle.get("slug").asText();
        MockHttpSession shopper = new MockHttpSession();
        call(shopper, get("/api/shop/catalog/products/" + slug), null).andExpect(status().isOk());

        try {
            call(admin, put("/api/shop/admin/settings/store"), Map.of("onsiteCheckout", false))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.affiliateOnly").value(true));
            call(shopper, get("/api/shop/catalog/store"), null).andExpect(jsonPath("$.affiliateOnly").value(true));
            call(shopper, get("/api/digital/catalog/store"), null).andExpect(jsonPath("$.affiliateOnly").value(false));
            call(shopper, get("/api/shop/catalog/products/" + slug), null).andExpect(status().isNotFound());
            call(admin, post("/api/shop/admin/products"), Map.of("productType", "PHYSICAL", "sellingMethod", "DIRECT",
                    "title", "Blocked", "priceMinor", 10000, "sku", "BLOCKED-1", "weightGrams", 10))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("AFFILIATE_ONLY"));
        } finally {
            call(admin, put("/api/shop/admin/settings/store"), Map.of("onsiteCheckout", true)).andExpect(status().isOk());
        }
        call(shopper, get("/api/shop/catalog/products/" + slug), null).andExpect(status().isOk());
    }

    private MockHttpSession master() throws Exception {
        String email = "channels-master@test.local";
        if (admins.findByStorefrontAndEmailIgnoreCase(Storefront.SHOP, email).isEmpty()) {
            admins.save(new AdminUser(Storefront.SHOP, email, passwords.encode(PASSWORD), "Channels", AdminRole.MASTER_ADMIN, false));
        }
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
