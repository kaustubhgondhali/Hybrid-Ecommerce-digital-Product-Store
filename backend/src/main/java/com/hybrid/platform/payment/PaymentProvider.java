package com.hybrid.platform.payment;

import java.util.Map;

/**
 * Port for payment gateways. Exactly one implementation is active, selected by app.payment.provider.
 * A Razorpay adapter will implement this interface; no other code needs to change.
 */
public interface PaymentProvider {

    record ProviderOrder(String providerOrderId, Map<String, Object> clientParams) {
    }

    record RefundResult(String providerRefundId) {
    }

    String code();

    /** True only for the demo provider: the storefront shows simulated success/failure buttons. */
    boolean isDemo();

    ProviderOrder createOrder(String receipt, long amountMinor, String currency);

    RefundResult refund(Payment payment, long amountMinor);
}
