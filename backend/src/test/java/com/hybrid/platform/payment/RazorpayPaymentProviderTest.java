package com.hybrid.platform.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.error.ApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RazorpayPaymentProviderTest {

    private static final String KEY_ID = "rzp_test_abcdef123456";
    private static final String KEY_SECRET = "key-secret";
    private static final String WEBHOOK_SECRET = "hook-secret";

    private HttpServer server;
    private RazorpayPaymentProvider provider;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final Map<String, String> paymentStatus = new ConcurrentHashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(ex.getRequestMethod() + " " + path + " " + body);
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String reply;
            int status = 200;
            if (auth == null || !auth.startsWith("Basic ")) {
                status = 401;
                reply = "{\"error\":{\"description\":\"Authentication failed\"}}";
            } else if (path.equals("/v1/orders")) {
                long amount = new ObjectMapper().readTree(body).path("amount").asLong();
                reply = "{\"id\":\"order_TEST1234\",\"amount\":" + amount + ",\"currency\":\"INR\",\"status\":\"created\"}";
            } else if (path.endsWith("/capture")) {
                paymentStatus.put("pay_AUTH1234", "captured");
                reply = payment("pay_AUTH1234", "captured");
            } else if (path.endsWith("/refund")) {
                reply = "{\"id\":\"rfnd_TEST1234\",\"amount\":5000}";
            } else if (path.startsWith("/v1/payments/")) {
                String id = path.substring("/v1/payments/".length());
                reply = payment(id, paymentStatus.getOrDefault(id, "captured"));
            } else {
                status = 404;
                reply = "{}";
            }
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        provider = new RazorpayPaymentProvider(new ObjectMapper(), new MockEnvironment(), KEY_ID, KEY_SECRET, WEBHOOK_SECRET,
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String payment(String id, String status) {
        return "{\"id\":\"" + id + "\",\"order_id\":\"order_TEST1234\",\"amount\":10000,\"currency\":\"INR\",\"status\":\""
                + status + "\",\"error_description\":" + ("failed".equals(status) ? "\"Card declined\"" : "null") + "}";
    }

    @Test
    void refusesToStartWithoutKeys() {
        assertThatThrownBy(() -> new RazorpayPaymentProvider(new ObjectMapper(), new MockEnvironment(), "", KEY_SECRET,
                WEBHOOK_SECRET, "http://localhost")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RazorpayPaymentProvider(new ObjectMapper(), new MockEnvironment(), KEY_ID, KEY_SECRET,
                " ", "http://localhost")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void createsOrderAndExposesOnlyPublicCheckoutValues() {
        PaymentProvider.ProviderOrder order = provider.createOrder("SHOP-2026-000123", 10000, "INR");
        assertThat(order.providerOrderId()).isEqualTo("order_TEST1234");
        assertThat(calls.get(0)).startsWith("POST /v1/orders").contains("\"amount\":10000").contains("SHOP-2026-000123");

        Payment p = new Payment(1L, Storefront.SHOP, "razorpay", order.providerOrderId(), 10000, "INR");
        Map<String, Object> client = provider.clientParams(p);
        assertThat(client).containsEntry("mode", "razorpay").containsEntry("key", KEY_ID)
                .containsEntry("orderId", "order_TEST1234").containsEntry("amount", 10000L);
        assertThat(client.values()).doesNotContain(KEY_SECRET, WEBHOOK_SECRET);
    }

    @Test
    void checkoutReturnNeedsRazorpaySignatureForThisOrder() {
        String sig = RazorpayPaymentProvider.hmacHex(KEY_SECRET, "order_TEST1234|pay_GOOD1234".getBytes(StandardCharsets.UTF_8));
        assertThat(provider.verifyCheckoutReturn("order_TEST1234", Map.of("razorpay_order_id", "order_TEST1234",
                "razorpay_payment_id", "pay_GOOD1234", "razorpay_signature", sig))).isEqualTo("pay_GOOD1234");

        assertThatThrownBy(() -> provider.verifyCheckoutReturn("order_TEST1234", Map.of("razorpay_order_id", "order_TEST1234",
                "razorpay_payment_id", "pay_OTHER123", "razorpay_signature", sig)))
                .isInstanceOf(ApiException.class).hasMessageContaining("not valid");
        assertThatThrownBy(() -> provider.verifyCheckoutReturn("order_ANOTHER1", Map.of("razorpay_order_id", "order_TEST1234",
                "razorpay_payment_id", "pay_GOOD1234", "razorpay_signature", sig)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> provider.verifyCheckoutReturn("order_TEST1234", Map.of())).isInstanceOf(ApiException.class);
    }

    @Test
    void settleCapturesAuthorizedPaymentsAndReportsFailures() {
        paymentStatus.put("pay_AUTH1234", "authorized");
        PaymentProvider.Settlement s = provider.settle("pay_AUTH1234");
        assertThat(s.status()).isEqualTo(PaymentProvider.SettlementStatus.CAPTURED);
        assertThat(s.amountMinor()).isEqualTo(10000);
        assertThat(s.providerOrderId()).isEqualTo("order_TEST1234");
        assertThat(calls).anyMatch(c -> c.startsWith("POST /v1/payments/pay_AUTH1234/capture") && c.contains("\"amount\":10000"));

        paymentStatus.put("pay_FAIL1234", "failed");
        PaymentProvider.Settlement f = provider.settle("pay_FAIL1234");
        assertThat(f.status()).isEqualTo(PaymentProvider.SettlementStatus.FAILED);
        assertThat(f.failureReason()).isEqualTo("Card declined");

        paymentStatus.put("pay_NEW12345", "created");
        assertThat(provider.settle("pay_NEW12345").status()).isEqualTo(PaymentProvider.SettlementStatus.PENDING);

        assertThatThrownBy(() -> provider.settle("../v1/orders")).isInstanceOf(ApiException.class);
    }

    @Test
    void webhooksMustBeSignedWithTheWebhookSecret() {
        byte[] body = ("{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":"
                + payment("pay_HOOK1234", "captured") + "}}}").getBytes(StandardCharsets.UTF_8);
        String sig = RazorpayPaymentProvider.hmacHex(WEBHOOK_SECRET, body);

        PaymentProvider.Notice n = provider.parseWebhook(body, sig).orElseThrow();
        assertThat(n.providerOrderId()).isEqualTo("order_TEST1234");
        assertThat(n.providerPaymentId()).isEqualTo("pay_HOOK1234");
        assertThat(n.failed()).isFalse();

        assertThatThrownBy(() -> provider.parseWebhook(body, RazorpayPaymentProvider.hmacHex(KEY_SECRET, body)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> provider.parseWebhook(body, null)).isInstanceOf(ApiException.class);

        byte[] failed = ("{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":"
                + payment("pay_FAIL1234", "failed") + "}}}").getBytes(StandardCharsets.UTF_8);
        PaymentProvider.Notice fn = provider.parseWebhook(failed, RazorpayPaymentProvider.hmacHex(WEBHOOK_SECRET, failed)).orElseThrow();
        assertThat(fn.failed()).isTrue();
        assertThat(fn.failureReason()).isEqualTo("Card declined");

        byte[] other = "{\"event\":\"refund.created\",\"payload\":{}}".getBytes(StandardCharsets.UTF_8);
        assertThat(provider.parseWebhook(other, RazorpayPaymentProvider.hmacHex(WEBHOOK_SECRET, other))).isEmpty();
    }

    @Test
    void refundsTheCapturedPayment() {
        Payment p = new Payment(1L, Storefront.SHOP, "razorpay", "order_TEST1234", 10000, "INR");
        p.setProviderPaymentId("pay_DONE1234");
        assertThat(provider.refund(p, 5000).providerRefundId()).isEqualTo("rfnd_TEST1234");
        assertThat(calls).anyMatch(c -> c.startsWith("POST /v1/payments/pay_DONE1234/refund") && c.contains("\"amount\":5000"));
    }
}
