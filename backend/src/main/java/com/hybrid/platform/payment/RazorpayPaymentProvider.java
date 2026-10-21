package com.hybrid.platform.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hybrid.platform.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Razorpay Standard Checkout over its REST API (no SDK). The browser opens Razorpay's checkout for an order created
 * here; the payment is then confirmed by checking Razorpay's signature and reading the payment back from the API,
 * and again by the signed webhook. Authorized payments are captured, so the dashboard capture setting does not matter.
 */
@Component
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "razorpay")
public class RazorpayPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(RazorpayPaymentProvider.class);
    static final Pattern KEY_ID = Pattern.compile("^rzp_(test|live)_[A-Za-z0-9]{6,40}$");
    static final Pattern PAYMENT_ID = Pattern.compile("^pay_[A-Za-z0-9]{6,40}$");
    static final Pattern ORDER_ID = Pattern.compile("^order_[A-Za-z0-9]{6,40}$");
    private static final int MAX_RECEIPT = 40;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json;
    private final String apiBase;
    private final String keyId;
    private final String keySecret;
    private final String webhookSecret;
    private final String authorization;

    public RazorpayPaymentProvider(ObjectMapper json, Environment env,
                                   @Value("${app.payment.razorpay.key-id:}") String keyId,
                                   @Value("${app.payment.razorpay.key-secret:}") String keySecret,
                                   @Value("${app.payment.razorpay.webhook-secret:}") String webhookSecret,
                                   @Value("${app.payment.razorpay.api-base:https://api.razorpay.com}") String apiBase) {
        if (keyId == null || !KEY_ID.matcher(keyId.trim()).matches()) {
            throw new IllegalStateException("RAZORPAY_KEY_ID is missing or invalid (expected rzp_test_... or rzp_live_...).");
        }
        if (keySecret == null || keySecret.isBlank()) {
            throw new IllegalStateException("RAZORPAY_KEY_SECRET is required when PAYMENT_PROVIDER=razorpay.");
        }
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalStateException("RAZORPAY_WEBHOOK_SECRET is required when PAYMENT_PROVIDER=razorpay.");
        }
        this.json = json;
        this.keyId = keyId.trim();
        this.keySecret = keySecret.trim();
        this.webhookSecret = webhookSecret.trim();
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        this.authorization = "Basic " + Base64.getEncoder()
                .encodeToString((this.keyId + ":" + this.keySecret).getBytes(StandardCharsets.UTF_8));
        boolean live = this.keyId.startsWith("rzp_live_");
        if (!live && env.acceptsProfiles(Profiles.of("prod"))) {
            log.warn("Razorpay TEST keys are active with the 'prod' profile: payments are not real.");
        }
        log.info("Razorpay payment provider active ({} mode).", live ? "live" : "test");
    }

    @Override
    public String code() {
        return "razorpay";
    }

    @Override
    public boolean isDemo() {
        return false;
    }

    @Override
    public ProviderOrder createOrder(String receipt, long amountMinor, String currency) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amountMinor);
        body.put("currency", currency);
        body.put("receipt", receipt.length() > MAX_RECEIPT ? receipt.substring(0, MAX_RECEIPT) : receipt);
        body.put("notes", Map.of("order_number", receipt));
        JsonNode order = call("POST", "/v1/orders", body);
        String id = order.path("id").asText("");
        if (!ORDER_ID.matcher(id).matches()) {
            throw gatewayError("Razorpay returned no order id");
        }
        if (order.path("amount").asLong(-1) != amountMinor) {
            throw gatewayError("Razorpay created the order with a different amount");
        }
        return new ProviderOrder(id, Map.of());
    }

    @Override
    public Map<String, Object> clientParams(Payment payment) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("mode", "razorpay");
        params.put("key", keyId);
        params.put("orderId", payment.getProviderOrderId());
        params.put("amount", payment.getAmountMinor());
        params.put("currency", payment.getCurrency());
        return params;
    }

    @Override
    public String verifyCheckoutReturn(String providerOrderId, Map<String, String> params) {
        String orderId = params.get("razorpay_order_id");
        String paymentId = params.get("razorpay_payment_id");
        String signature = params.get("razorpay_signature");
        if (paymentId == null || !PAYMENT_ID.matcher(paymentId).matches() || signature == null
                || !providerOrderId.equals(orderId)) {
            throw ApiException.badRequest("PAYMENT_SIGNATURE_INVALID", "The payment confirmation is not valid");
        }
        if (!signatureMatches(hmacHex(keySecret, (providerOrderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8)), signature)) {
            log.warn("Rejected a Razorpay checkout return with a bad signature for {}", providerOrderId);
            throw ApiException.badRequest("PAYMENT_SIGNATURE_INVALID", "The payment confirmation is not valid");
        }
        return paymentId;
    }

    @Override
    public Settlement settle(String providerPaymentId) {
        if (providerPaymentId == null || !PAYMENT_ID.matcher(providerPaymentId).matches()) {
            throw ApiException.badRequest("PAYMENT_ID_INVALID", "Invalid payment id");
        }
        JsonNode payment = call("GET", "/v1/payments/" + providerPaymentId, null);
        if ("authorized".equals(payment.path("status").asText())) {
            try {
                payment = call("POST", "/v1/payments/" + providerPaymentId + "/capture",
                        Map.of("amount", payment.path("amount").asLong(), "currency", payment.path("currency").asText()));
            } catch (ApiException e) {
                // Captured meanwhile by the webhook or the dashboard's automatic capture.
                payment = call("GET", "/v1/payments/" + providerPaymentId, null);
            }
        }
        String orderId = payment.path("order_id").asText(null);
        long amount = payment.path("amount").asLong(0);
        return switch (payment.path("status").asText("")) {
            case "captured", "refunded" -> new Settlement(orderId, providerPaymentId, SettlementStatus.CAPTURED, amount, null);
            case "failed" -> new Settlement(orderId, providerPaymentId, SettlementStatus.FAILED, 0,
                    payment.path("error_description").asText(null));
            default -> new Settlement(orderId, providerPaymentId, SettlementStatus.PENDING, amount, null);
        };
    }

    @Override
    public Optional<Notice> parseWebhook(byte[] body, String signature) {
        if (signature == null || !signatureMatches(hmacHex(webhookSecret, body), signature)) {
            log.warn("Rejected a Razorpay webhook with a missing or bad signature");
            throw ApiException.unauthorized("WEBHOOK_SIGNATURE_INVALID", "Invalid signature");
        }
        JsonNode event;
        try {
            event = json.readTree(body);
        } catch (IOException e) {
            throw ApiException.badRequest("WEBHOOK_INVALID", "Invalid webhook body");
        }
        String type = event.path("event").asText("");
        JsonNode payment = event.path("payload").path("payment").path("entity");
        String paymentId = payment.path("id").asText(null);
        String orderId = payment.path("order_id").asText(null);
        if (paymentId == null || orderId == null) {
            return Optional.empty();
        }
        return switch (type) {
            case "payment.authorized", "payment.captured", "order.paid" -> Optional.of(new Notice(orderId, paymentId, false, null));
            case "payment.failed" -> Optional.of(new Notice(orderId, paymentId, true, payment.path("error_description").asText(null)));
            default -> Optional.empty();
        };
    }

    @Override
    public RefundResult refund(Payment payment, long amountMinor) {
        String paymentId = payment.getProviderPaymentId();
        if (paymentId == null || !PAYMENT_ID.matcher(paymentId).matches()) {
            throw ApiException.conflict("PAYMENT_NOT_REFUNDABLE", "This payment has no Razorpay payment id");
        }
        JsonNode refund = call("POST", "/v1/payments/" + paymentId + "/refund", Map.of("amount", amountMinor));
        String id = refund.path("id").asText("");
        if (id.isEmpty()) {
            throw gatewayError("Razorpay returned no refund id");
        }
        return new RefundResult(id);
    }

    private JsonNode call(String method, String path, Object body) {
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(apiBase + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", authorization)
                    .header("Accept", "application/json");
            if (body == null) {
                req.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                req.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
            }
            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode reply = res.body() == null || res.body().isBlank() ? json.nullNode() : json.readTree(res.body());
            if (res.statusCode() / 100 != 2) {
                String reason = reply.path("error").path("description").asText("HTTP " + res.statusCode());
                log.warn("Razorpay {} {} failed: {}", method, path, reason);
                throw gatewayError("Razorpay: " + reason);
            }
            return reply;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw gatewayError("Interrupted while contacting Razorpay");
        } catch (IOException e) {
            log.warn("Razorpay {} {} failed: {}", method, path, e.getClass().getSimpleName());
            throw gatewayError("Could not reach Razorpay. Please try again.");
        }
    }

    private static ApiException gatewayError(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENT_GATEWAY_ERROR", message);
    }

    static String hmacHex(String secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean signatureMatches(String expectedHex, String given) {
        return MessageDigest.isEqual(expectedHex.getBytes(StandardCharsets.US_ASCII),
                given.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }
}
