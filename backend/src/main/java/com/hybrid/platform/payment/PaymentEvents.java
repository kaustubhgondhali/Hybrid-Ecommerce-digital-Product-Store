package com.hybrid.platform.payment;

public final class PaymentEvents {

    private PaymentEvents() {
    }

    public record PaymentSucceeded(Long orderId, Long paymentId, long amountMinor, String currency) {
    }

    public record PaymentFailed(Long orderId, Long paymentId, String reason) {
    }
}
