package com.hybrid.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** End-to-end flow through the real HTTP layer, security filters and database (H2 in MySQL mode). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CommerceFlowIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    @Order(1)
    void fullPurchaseFlowAcrossAllProductCombinations() throws Exception {
        // ---- shop admin: first login forces a password change
        MockHttpSession admin = new MockHttpSession();
        call(admin, post("/api/shop/admin/auth/login"), Map.of("email", "shop-admin@test.local", "password", "TestOnly-Passw0rd"))
                .andExpect(status().isOk());
        call(admin, get("/api/shop/admin/products"), null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        call(admin, post("/api/shop/admin/auth/change-password"),
                Map.of("currentPassword", "TestOnly-Passw0rd", "newPassword", "Changed-Passw0rd-1"))
                .andExpect(status().is2xxSuccessful());

        // ---- PHYSICAL + DIRECT
        JsonNode mug = body(call(admin, post("/api/shop/admin/products"), Map.of(
                "productType", "PHYSICAL", "sellingMethod", "DIRECT", "title", "Test Mug",
                "priceMinor", 49900, "sku", "MUG-1", "weightGrams", 300)).andExpect(status().isCreated()));
        long mugId = mug.get("id").asLong();
        long mugVariant = mug.get("variants").get(0).get("id").asLong();
        call(admin, post("/api/shop/admin/products/" + mugId + "/status"), Map.of("status", "PUBLISHED"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PUBLISH_BLOCKED"));
        uploadImage(admin, "/api/shop/admin/products/" + mugId + "/images");
        call(admin, post("/api/shop/admin/products/" + mugId + "/status"), Map.of("status", "PUBLISHED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"));
        call(admin, post("/api/shop/admin/inventory/" + mugVariant + "/adjust"), Map.of("delta", 5, "reason", "RECEIPT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(5));

        // ---- PHYSICAL + AFFILIATE (any merchant URL, new domain needs explicit confirmation)
        JsonNode phones = body(call(admin, post("/api/shop/admin/products"), Map.of(
                "productType", "PHYSICAL", "sellingMethod", "AFFILIATE", "title", "Partner Headphones"))
                .andExpect(status().isCreated()));
        long phonesId = phones.get("id").asLong();
        long phonesVariant = phones.get("variants").get(0).get("id").asLong();
        uploadImage(admin, "/api/shop/admin/products/" + phonesId + "/images");
        String affiliateUrl = "https://www.amazon.in/dp/B000TEST?tag=example-21";
        call(admin, put("/api/shop/admin/products/" + phonesId + "/affiliate-link"), Map.of("url", affiliateUrl))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AFFILIATE_DOMAIN_UNCONFIRMED"));
        JsonNode link = body(call(admin, put("/api/shop/admin/products/" + phonesId + "/affiliate-link"),
                Map.of("url", affiliateUrl, "confirmNewDomain", true)).andExpect(status().isOk()));
        call(admin, post("/api/shop/admin/products/" + phonesId + "/status"), Map.of("status", "PUBLISHED"))
                .andExpect(status().isOk());

        // ---- the shop admin has no access to the digital admin
        call(admin, get("/api/digital/admin/products"), null).andExpect(status().isUnauthorized());

        // ---- customer
        MockHttpSession buyer = new MockHttpSession();
        call(buyer, post("/api/auth/register"), Map.of("email", "buyer@test.local", "password", "Buyer-Passw0rd1",
                "fullName", "Test Buyer", "storefront", "shop")).andExpect(status().isCreated());
        call(buyer, get("/api/shop/catalog/products/" + mug.get("slug").asText()), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Test Mug"));
        call(buyer, get("/api/books/catalog/products"), null).andExpect(status().isNotFound());

        // Affiliate products can sit in the cart for later partner purchase (not on-site checkout).
        JsonNode affiliateCart = body(call(buyer, post("/api/shop/cart/items"), Map.of("variantId", phonesVariant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasAffiliateItems").value(true))
                .andExpect(jsonPath("$.items[0].sellingMethod").value("AFFILIATE"))
                .andExpect(jsonPath("$.items[0].goUrl").isNotEmpty()));
        call(buyer, delete("/api/shop/cart/items/" + affiliateCart.get("items").get(0).get("itemId").asLong()), null)
                .andExpect(status().isOk());

        call(buyer, post("/api/shop/cart/items"), Map.of("variantId", mugVariant, "quantity", 9))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("OUT_OF_STOCK"));
        call(buyer, post("/api/shop/cart/items"), Map.of("variantId", mugVariant, "quantity", 2))
                .andExpect(status().isOk()).andExpect(jsonPath("$.subtotalMinor").value(99800));

        JsonNode quote = body(call(buyer, post("/api/shop/checkout/quote"), Map.of()).andExpect(status().isOk()));
        assertThat(quote.get("shippingMinor").asLong()).isEqualTo(5000);
        assertThat(quote.get("totalMinor").asLong()).isEqualTo(104800);

        Map<String, Object> checkout = Map.of("shippingMethodId", quote.get("shippingMethodId").asLong(),
                "address", Map.of("name", "Test Buyer", "phone", "+91 98765 43210", "line1", "1 Test Street",
                        "city", "Pune", "state", "Maharashtra", "postalCode", "411001", "country", "IN"));
        JsonNode placed = body(call(buyer, post("/api/shop/checkout/orders").header("Idempotency-Key", "test-key-0001"), checkout)
                .andExpect(status().isCreated()));
        String orderNumber = placed.get("orderNumber").asText();
        assertThat(placed.get("totalMinor").asLong()).isEqualTo(104800);
        assertThat(placed.get("payment").get("demo").asBoolean()).isTrue();

        JsonNode again = body(call(buyer, post("/api/shop/checkout/orders").header("Idempotency-Key", "test-key-0001"), checkout)
                .andExpect(status().isCreated()));
        assertThat(again.get("orderNumber").asText()).isEqualTo(orderNumber);

        call(admin, get("/api/shop/admin/inventory"), null).andExpect(jsonPath("$[0].reserved").value(2));

        // ---- demo payment: declined first, then succeeds
        call(buyer, post("/api/payments/demo/" + orderNumber + "/complete"), Map.of("outcome", "FAILURE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paymentStatus").value("FAILED"));
        call(buyer, post("/api/orders/" + orderNumber + "/payment/retry"), null).andExpect(status().isOk());
        call(buyer, post("/api/payments/demo/" + orderNumber + "/complete"), Map.of("outcome", "SUCCESS"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));

        JsonNode order = body(call(buyer, get("/api/orders/" + orderNumber), null).andExpect(status().isOk()));
        assertThat(order.get("status").asText()).isEqualTo("PAID");
        assertThat(order.get("invoiceNumber").asText()).startsWith("SHP-");
        call(buyer, get("/api/orders/" + orderNumber + "/invoice"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMinor").value(104800));
        call(admin, get("/api/shop/admin/inventory"), null)
                .andExpect(jsonPath("$[0].onHand").value(3)).andExpect(jsonPath("$[0].reserved").value(0));

        // ---- admin ships the order
        JsonNode adminOrders = body(call(admin, get("/api/shop/admin/orders"), null).andExpect(status().isOk()));
        long orderId = adminOrders.get("items").get(0).get("id").asLong();
        call(admin, post("/api/shop/admin/orders/" + orderId + "/shipments"),
                Map.of("carrier", "BlueDart", "trackingNumber", "BD123", "trackingUrl", "https://example.com/track/BD123"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.order.status").value("SHIPPED"));
        call(admin, get("/api/shop/admin/dashboard"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.revenue30dMinor").value(104800));

        // ---- affiliate redirect goes straight to the merchant URL
        mvc.perform(get("/go/" + link.get("code").asText()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", affiliateUrl));
    }

    @Test
    @Order(2)
    void digitalDirectProductDeliversLicenseKeyAfterPayment() throws Exception {
        MockHttpSession admin = new MockHttpSession();
        call(admin, post("/api/digital/admin/auth/login"), Map.of("email", "digital-admin@test.local", "password", "TestOnly-Passw0rd"))
                .andExpect(status().isOk());
        call(admin, post("/api/digital/admin/auth/change-password"),
                Map.of("currentPassword", "TestOnly-Passw0rd", "newPassword", "Changed-Passw0rd-2"))
                .andExpect(status().is2xxSuccessful());
        call(admin, get("/api/shop/admin/products"), null).andExpect(status().isUnauthorized());

        JsonNode ebook = body(call(admin, post("/api/digital/admin/products"), Map.of(
                "productType", "DIGITAL", "sellingMethod", "DIRECT", "title", "Pro Software Licence",
                "deliveryKind", "LICENSE_KEY", "priceMinor", 19900)).andExpect(status().isCreated()));
        long id = ebook.get("id").asLong();
        long variant = ebook.get("variants").get(0).get("id").asLong();
        uploadImage(admin, "/api/digital/admin/products/" + id + "/images");
        call(admin, post("/api/digital/admin/products/" + id + "/license-keys"), Map.of("keys", "KEY-AAAA-0001\nKEY-AAAA-0002"))
                .andExpect(status().isOk());
        call(admin, post("/api/digital/admin/products/" + id + "/status"), Map.of("status", "PUBLISHED"))
                .andExpect(status().isOk());

        MockHttpSession buyer = new MockHttpSession();
        call(buyer, post("/api/auth/register"), Map.of("email", "reader@test.local", "password", "Reader-Passw0rd1",
                "fullName", "Test Reader", "storefront", "digital")).andExpect(status().isCreated());
        call(buyer, post("/api/digital/cart/items"), Map.of("variantId", variant)).andExpect(status().isOk());
        JsonNode placed = body(call(buyer, post("/api/digital/checkout/orders").header("Idempotency-Key", "digital-key-0001"), Map.of())
                .andExpect(status().isCreated()));
        String orderNumber = placed.get("orderNumber").asText();
        assertThat(placed.get("totalMinor").asLong()).isEqualTo(19900);
        call(buyer, post("/api/payments/demo/" + orderNumber + "/complete"), Map.of("outcome", "SUCCESS"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));

        JsonNode library = body(call(buyer, get("/api/library"), null).andExpect(status().isOk()));
        assertThat(library).hasSize(1);
        assertThat(library.get(0).get("licenseKeys").get(0).asText()).startsWith("KEY-AAAA-");
    }

    @Test
    @Order(3)
    void couponWishlistAndCashOnDelivery() throws Exception {
        MockHttpSession admin = new MockHttpSession();
        call(admin, post("/api/shop/admin/auth/login"), Map.of("email", "shop-admin@test.local", "password", "Changed-Passw0rd-1"))
                .andExpect(status().isOk());
        call(admin, put("/api/shop/admin/settings/store"), Map.of("codEnabled", true, "codFeeMinor", 4000))
                .andExpect(status().isOk()).andExpect(jsonPath("$.codEnabled").value(true));
        call(admin, post("/api/shop/admin/coupons"), Map.of("code", "save10", "discountType", "PERCENT",
                "discountValue", 1000, "perCustomerLimit", 1)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SAVE10"));

        JsonNode kettle = body(call(admin, post("/api/shop/admin/products"), Map.of(
                "productType", "PHYSICAL", "sellingMethod", "DIRECT", "title", "Steel Kettle",
                "priceMinor", 100000, "sku", "KETTLE-1", "weightGrams", 900)).andExpect(status().isCreated()));
        long kettleId = kettle.get("id").asLong();
        long kettleVariant = kettle.get("variants").get(0).get("id").asLong();
        String slug = kettle.get("slug").asText();
        uploadImage(admin, "/api/shop/admin/products/" + kettleId + "/images");
        call(admin, post("/api/shop/admin/products/" + kettleId + "/status"), Map.of("status", "PUBLISHED")).andExpect(status().isOk());
        call(admin, post("/api/shop/admin/inventory/" + kettleVariant + "/adjust"), Map.of("delta", 3, "reason", "RECEIPT"))
                .andExpect(status().isOk());

        MockHttpSession buyer = new MockHttpSession();
        call(buyer, post("/api/auth/register"), Map.of("email", "cod-buyer@test.local", "password", "CodBuyer-Passw0rd1",
                "fullName", "Cod Buyer", "storefront", "shop")).andExpect(status().isCreated());

        // ---- wishlist is per customer and per storefront
        call(new MockHttpSession(), get("/api/shop/wishlist"), null).andExpect(status().isUnauthorized());
        call(buyer, put("/api/shop/wishlist/" + slug), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(true)).andExpect(jsonPath("$.count").value(1));
        call(buyer, put("/api/shop/wishlist/" + slug), null).andExpect(jsonPath("$.count").value(1));
        call(buyer, get("/api/shop/wishlist"), null).andExpect(status().isOk()).andExpect(jsonPath("$[0].slug").value(slug));
        call(buyer, put("/api/digital/wishlist/" + slug), null).andExpect(status().isNotFound());
        call(buyer, delete("/api/shop/wishlist/" + slug), null).andExpect(jsonPath("$.saved").value(false))
                .andExpect(jsonPath("$.count").value(0));

        // ---- coupon + COD quote: the server computes discount and fee, the client only names them
        call(buyer, post("/api/shop/cart/items"), Map.of("variantId", kettleVariant, "quantity", 1)).andExpect(status().isOk());
        call(buyer, post("/api/shop/checkout/coupon"), Map.of("code", "nope")).andExpect(status().is4xxClientError());
        call(buyer, post("/api/shop/checkout/coupon"), Map.of("code", " save10 ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.discountMinor").value(10000));
        JsonNode quote = body(call(buyer, post("/api/shop/checkout/quote"),
                Map.of("couponCode", "SAVE10", "paymentMethod", "COD", "buyerStateCode", "27")).andExpect(status().isOk()));
        assertThat(quote.get("codAvailable").asBoolean()).isTrue();
        assertThat(quote.get("discountMinor").asLong()).isEqualTo(10000);
        assertThat(quote.get("codFeeMinor").asLong()).isEqualTo(4000);
        long expectedTotal = 100000 - 10000 + quote.get("shippingMinor").asLong() + 4000;
        assertThat(quote.get("totalMinor").asLong()).isEqualTo(expectedTotal);

        Map<String, Object> checkout = Map.of("shippingMethodId", quote.get("shippingMethodId").asLong(),
                "couponCode", "SAVE10", "paymentMethod", "COD",
                "address", Map.of("name", "Cod Buyer", "phone", "+91 98765 43210", "line1", "2 Test Street",
                        "city", "Mumbai", "state", "Maharashtra", "postalCode", "400001", "country", "IN"));
        JsonNode placed = body(call(buyer, post("/api/shop/checkout/orders").header("Idempotency-Key", "cod-key-0001"), checkout)
                .andExpect(status().isCreated()));
        String orderNumber = placed.get("orderNumber").asText();
        assertThat(placed.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(placed.get("totalMinor").asLong()).isEqualTo(expectedTotal);

        // the coupon is reserved by the order, so the per-customer limit already applies
        call(buyer, post("/api/shop/checkout/coupon"), Map.of("code", "SAVE10")).andExpect(status().is4xxClientError());

        JsonNode order = body(call(buyer, get("/api/orders/" + orderNumber), null).andExpect(status().isOk()));
        assertThat(order.get("couponCode").asText()).isEqualTo("SAVE10");
        assertThat(order.get("codFeeMinor").asLong()).isEqualTo(4000);
        assertThat(order.get("cgstMinor").asLong()).isEqualTo(order.get("sgstMinor").asLong());

        // ---- admin ships, then delivery records the cash, issues the invoice and uses the coupon
        JsonNode adminOrders = body(call(admin, get("/api/shop/admin/orders"), null).andExpect(status().isOk()));
        long orderId = -1;
        for (JsonNode o : adminOrders.get("items")) {
            if (o.get("orderNumber").asText().equals(orderNumber)) orderId = o.get("id").asLong();
        }
        assertThat(orderId).isPositive();
        call(admin, post("/api/shop/admin/orders/" + orderId + "/shipments"), Map.of("carrier", "DTDC", "trackingNumber", "D1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.order.status").value("SHIPPED"));
        call(admin, post("/api/shop/admin/orders/" + orderId + "/delivered"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.order.status").value("DELIVERED"));
        call(buyer, get("/api/orders/" + orderNumber + "/invoice"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMinor").value(expectedTotal));
        JsonNode coupons = body(call(admin, get("/api/shop/admin/coupons"), null).andExpect(status().isOk()));
        assertThat(coupons.get(0).get("timesUsed").asLong()).isEqualTo(1);
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
