package com.hybrid.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.identity.AdminRole;
import com.hybrid.platform.identity.AdminUser;
import com.hybrid.platform.identity.AdminUserRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Checkout through Razorpay against a fake Razorpay API: signed return, capture, webhooks, failure, retry and refund. */
@SpringBootTest(properties = {
        "app.payment.provider=razorpay",
        "app.payment.razorpay.key-id=rzp_test_integration1",
        "app.payment.razorpay.key-secret=" + RazorpayPaymentIntegrationTest.KEY_SECRET,
        "app.payment.razorpay.webhook-secret=" + RazorpayPaymentIntegrationTest.WEBHOOK_SECRET
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RazorpayPaymentIntegrationTest {

    static final String KEY_SECRET = "it-key-secret";
    static final String WEBHOOK_SECRET = "it-webhook-secret";
    private static final String PASSWORD = "Razorpay-Test-Passw0rd-1";
    private static final String ADMIN = "/api/digital/admin";

    private record FakePayment(String orderId, long amount, String status) {
    }

    private static final HttpServer RAZORPAY;
    private static final AtomicInteger ORDER_SEQ = new AtomicInteger();
    private static final Map<String, Long> ORDERS = new ConcurrentHashMap<>();
    private static final Map<String, FakePayment> PAYMENTS = new ConcurrentHashMap<>();
    private static final List<String> CALLS = new CopyOnWriteArrayList<>();

    static {
        try {
            RAZORPAY = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ObjectMapper mapper = new ObjectMapper();
        RAZORPAY.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            CALLS.add(ex.getRequestMethod() + " " + path + " " + body);
            String reply;
            if (path.equals("/v1/orders")) {
                String id = "order_IT" + String.format("%06d", ORDER_SEQ.incrementAndGet());
                long amount = mapper.readTree(body).path("amount").asLong();
                ORDERS.put(id, amount);
                reply = "{\"id\":\"" + id + "\",\"amount\":" + amount + ",\"currency\":\"INR\",\"status\":\"created\"}";
            } else if (path.matches("/v1/payments/pay_\\w+/capture")) {
                String id = path.split("/")[3];
                FakePayment p = PAYMENTS.get(id);
                PAYMENTS.put(id, new FakePayment(p.orderId(), p.amount(), "captured"));
                reply = paymentJson(id);
            } else if (path.matches("/v1/payments/pay_\\w+/refund")) {
                reply = "{\"id\":\"rfnd_IT000001\",\"amount\":" + mapper.readTree(body).path("amount").asLong() + "}";
            } else if (path.matches("/v1/payments/pay_\\w+")) {
                reply = paymentJson(path.split("/")[3]);
            } else {
                reply = "{}";
            }
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        RAZORPAY.start();
    }

    @DynamicPropertySource
    static void razorpayApi(DynamicPropertyRegistry registry) {
        registry.add("app.payment.razorpay.api-base", () -> "http://127.0.0.1:" + RAZORPAY.getAddress().getPort());
    }

    @AfterAll
    static void stopFakeRazorpay() {
        RAZORPAY.stop(0);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    AdminUserRepository admins;
    @Autowired
    PasswordEncoder passwords;

    @Test
    void customerPaysThroughRazorpayAndTheStoreCanRefund() throws Exception {
        if (admins.findByStorefrontAndEmailIgnoreCase(Storefront.DIGITAL, "razorpay-master@test.local").isEmpty()) {
            admins.save(new AdminUser(Storefront.DIGITAL, "razorpay-master@test.local", passwords.encode(PASSWORD), "Razorpay",
                    AdminRole.MASTER_ADMIN, false));
        }
        MockHttpSession admin = new MockHttpSession();
        call(admin, post(ADMIN + "/auth/login"), Map.of("email", "razorpay-master@test.local", "password", PASSWORD))
                .andExpect(status().isOk());
        JsonNode product = body(call(admin, post(ADMIN + "/products"), Map.of("productType", "DIGITAL", "sellingMethod", "DIRECT",
                "title", "Razorpay Test Licence", "deliveryKind", "LICENSE_KEY", "priceMinor", 29900)).andExpect(status().isCreated()));
        long productId = product.get("id").asLong();
        long variant = product.get("variants").get(0).get("id").asLong();
        uploadImage(admin, ADMIN + "/products/" + productId + "/images");
        call(admin, post(ADMIN + "/products/" + productId + "/license-keys"), Map.of("keys", "RZP-KEY-0001\nRZP-KEY-0002\nRZP-KEY-0003"))
                .andExpect(status().isOk());
        call(admin, post(ADMIN + "/products/" + productId + "/status"), Map.of("status", "PUBLISHED")).andExpect(status().isOk());

        MockHttpSession buyer = new MockHttpSession();
        call(buyer, post("/api/auth/register"), Map.of("email", "razorpay-buyer@test.local", "password", "Buyer-Passw0rd1",
                "fullName", "Razorpay Buyer", "storefront", "digital")).andExpect(status().isCreated());

        // ---- placing the order creates a Razorpay order for the exact total; the browser gets only public values
        String orderNumber = placeOrder(buyer, variant, "rzp-it-0001");
        JsonNode pay = body(call(buyer, get("/api/orders/" + orderNumber + "/payment"), null).andExpect(status().isOk()));
        assertThat(pay.get("demo").asBoolean()).isFalse();
        JsonNode client = pay.get("payment").get("clientParams");
        assertThat(client.get("mode").asText()).isEqualTo("razorpay");
        assertThat(client.get("key").asText()).isEqualTo("rzp_test_integration1");
        assertThat(client.get("amount").asLong()).isEqualTo(29900);
        assertThat(client.toString()).doesNotContain(KEY_SECRET).doesNotContain(WEBHOOK_SECRET);
        String rzpOrder = client.get("orderId").asText();
        assertThat(ORDERS).containsEntry(rzpOrder, 29900L);

        call(buyer, post("/api/payments/demo/" + orderNumber + "/complete"), Map.of("outcome", "SUCCESS"))
                .andExpect(status().isNotFound());

        // ---- the payment page may load Razorpay's checkout; other pages keep the strict policy
        String payCsp = mvc.perform(get("/digital/pay/" + orderNumber)).andReturn().getResponse().getHeader("Content-Security-Policy");
        assertThat(payCsp).contains("script-src 'self'").contains("https://*.razorpay.com").contains("frame-src");
        assertThat(mvc.perform(get("/digital")).andReturn().getResponse().getHeader("Content-Security-Policy"))
                .doesNotContain("razorpay");

        // ---- a forged confirmation is rejected and changes nothing
        PAYMENTS.put("pay_ITFORGED1", new FakePayment(rzpOrder, 29900, "captured"));
        call(buyer, post("/api/orders/" + orderNumber + "/payment/confirm"), Map.of("razorpay_order_id", rzpOrder,
                "razorpay_payment_id", "pay_ITFORGED1", "razorpay_signature", "00" + hmac(KEY_SECRET, rzpOrder + "|pay_ITFORGED1").substring(2)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAYMENT_SIGNATURE_INVALID"));
        call(buyer, get("/api/orders/" + orderNumber), null).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));

        // ---- a genuine, only-authorized payment is captured by the server and completes the order
        PAYMENTS.put("pay_ITGOOD001", new FakePayment(rzpOrder, 29900, "authorized"));
        call(buyer, post("/api/orders/" + orderNumber + "/payment/confirm"), Map.of("razorpay_order_id", rzpOrder,
                "razorpay_payment_id", "pay_ITGOOD001", "razorpay_signature", hmac(KEY_SECRET, rzpOrder + "|pay_ITGOOD001")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(CALLS).anyMatch(c -> c.startsWith("POST /v1/payments/pay_ITGOOD001/capture") && c.contains("\"amount\":29900"));

        // ---- the webhook for the same payment arrives later and is a no-op; bad or missing signatures are refused
        byte[] captured = webhook("payment.captured", "pay_ITGOOD001");
        webhookCall(captured, hmac(WEBHOOK_SECRET, captured)).andExpect(status().isOk());
        webhookCall(captured, hmac(KEY_SECRET, captured)).andExpect(status().isUnauthorized());
        webhookCall(captured, null).andExpect(status().isUnauthorized());
        JsonNode library = body(call(buyer, get("/api/library"), null).andExpect(status().isOk()));
        assertThat(library).hasSize(1);
        assertThat(library.get(0).get("licenseKeys")).hasSize(1);

        // ---- events for orders this shop never created are acknowledged and ignored
        PAYMENTS.put("pay_ITSTRAY01", new FakePayment("order_SOMEONEELSE", 100, "captured"));
        byte[] stray = webhook("payment.captured", "pay_ITSTRAY01");
        webhookCall(stray, hmac(WEBHOOK_SECRET, stray)).andExpect(status().isOk());

        // ---- the admin refunds part of it through Razorpay
        long orderId = body(call(admin, get(ADMIN + "/orders").param("q", orderNumber), null).andExpect(status().isOk()))
                .get("items").get(0).get("id").asLong();
        call(admin, post(ADMIN + "/orders/" + orderId + "/refund"), Map.of("amountMinor", 9900, "restock", false, "reason", "Goodwill"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.payment.status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.payment.refundedMinor").value(9900));
        assertThat(CALLS).anyMatch(c -> c.startsWith("POST /v1/payments/pay_ITGOOD001/refund") && c.contains("\"amount\":9900"));

        // ---- a declined attempt reported by webhook, then a retry on the same Razorpay order paid via webhook only
        String second = placeOrder(buyer, variant, "rzp-it-0002");
        String secondRzpOrder = body(call(buyer, get("/api/orders/" + second + "/payment"), null))
                .get("payment").get("clientParams").get("orderId").asText();
        PAYMENTS.put("pay_ITFAIL001", new FakePayment(secondRzpOrder, 29900, "failed"));
        byte[] failed = webhook("payment.failed", "pay_ITFAIL001");
        webhookCall(failed, hmac(WEBHOOK_SECRET, failed)).andExpect(status().isOk());
        call(buyer, get("/api/orders/" + second + "/payment"), null)
                .andExpect(jsonPath("$.paymentStatus").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("Payment declined by bank"));
        call(buyer, post("/api/orders/" + second + "/payment/retry"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.clientParams.orderId").value(secondRzpOrder));

        PAYMENTS.put("pay_ITLATE001", new FakePayment(secondRzpOrder, 29900, "captured"));
        byte[] paid = webhook("order.paid", "pay_ITLATE001");
        webhookCall(paid, hmac(WEBHOOK_SECRET, paid)).andExpect(status().isOk());
        call(buyer, get("/api/orders/" + second), null).andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    private String placeOrder(MockHttpSession buyer, long variant, String idempotencyKey) throws Exception {
        call(buyer, post("/api/digital/cart/items"), Map.of("variantId", variant)).andExpect(status().isOk());
        JsonNode placed = body(call(buyer, post("/api/digital/checkout/orders").header("Idempotency-Key", idempotencyKey), Map.of())
                .andExpect(status().isCreated()));
        return placed.get("orderNumber").asText();
    }

    private static String paymentJson(String id) {
        FakePayment p = PAYMENTS.get(id);
        if (p == null) return "{\"error\":{\"description\":\"not found\"}}";
        return "{\"id\":\"" + id + "\",\"entity\":\"payment\",\"order_id\":\"" + p.orderId() + "\",\"amount\":" + p.amount()
                + ",\"currency\":\"INR\",\"status\":\"" + p.status() + "\",\"error_description\":"
                + ("failed".equals(p.status()) ? "\"Payment declined by bank\"" : "null") + "}";
    }

    private static byte[] webhook(String event, String paymentId) {
        return ("{\"entity\":\"event\",\"event\":\"" + event + "\",\"payload\":{\"payment\":{\"entity\":" + paymentJson(paymentId) + "}}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private ResultActions webhookCall(byte[] body, String signature) throws Exception {
        MockHttpServletRequestBuilder req = post("/api/payments/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).content(body);
        if (signature != null) req.header("X-Razorpay-Signature", signature);
        return mvc.perform(req);
    }

    private static String hmac(String secret, String data) {
        return hmac(secret, data.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmac(String secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
