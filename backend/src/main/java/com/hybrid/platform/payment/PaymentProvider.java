package com.hybrid.platform.payment;

import com.hybrid.platform.common.error.ApiException;

import java.util.Map;
import java.util.Optional;

/**
 * Port for payment gateways. Exactly one implementation is active, selected by app.payment.provider.
 */
public interface PaymentProvider {

    record ProviderOrder(String providerOrderId, Map<String, Object> clientParams) {
    }

    record RefundResult(String providerRefundId) {
    }

    /** A gateway notification whose signature has been verified. */
    record Notice(String providerOrderId, String providerPaymentId, boolean failed, String failureReason) {
    }

    enum SettlementStatus { CAPTURED, FAILED, PENDING }

    /** The gateway's own record of a payment, read from its API (never from the browser). */
    record Settlement(String providerOrderId, String providerPaymentId, SettlementStatus status, long amountMinor,
                      String failureReason) {
    }

    String code();

    /** True only for the demo provider: the storefront shows simulated success/failure buttons. */
    boolean isDemo();

    ProviderOrder createOrder(String receipt, long amountMinor, String currency);

    RefundResult refund(Payment payment, long amountMinor);

    /** What the browser needs to open the gateway's checkout for this payment. Contains no secrets. */
    default Map<String, Object> clientParams(Payment payment) {
        return Map.of("mode", isDemo() ? "demo" : "live");
    }

    /** Checks the signed values the gateway's checkout handed to the browser; returns the provider payment id. */
    default String verifyCheckoutReturn(String providerOrderId, Map<String, String> params) {
        throw ApiException.notFound("NOT_FOUND", "Not found");
    }

    /** Fetches the payment from the gateway and captures it if it is only authorized. */
    default Settlement settle(String providerPaymentId) {
        throw ApiException.notFound("NOT_FOUND", "Not found");
    }

    /** Verifies and parses a webhook; empty for events this application does not act on. */
    default Optional<Notice> parseWebhook(byte[] body, String signature) {
        throw ApiException.notFound("NOT_FOUND", "Not found");
    }
}
