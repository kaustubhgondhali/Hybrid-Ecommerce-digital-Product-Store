package com.hybrid.platform.commerce.order;

import com.hybrid.platform.common.Storefront;

import java.util.List;

/**
 * Domain events published by the order lifecycle. Listeners that change state (digital delivery) run
 * synchronously inside the same transaction; notification listeners run after commit.
 */
public final class OrderEvents {

    private OrderEvents() {
    }

    public record DigitalLine(Long orderLineId, Long productId, String title, int quantity) {
    }

    public record OrderPaid(Long orderId, String orderNumber, Storefront storefront, Long customerId,
                            String customerEmail, String customerName, long totalMinor, String currency,
                            boolean requiresShipping, List<DigitalLine> digitalLines) {
    }

    public record OrderRefunded(Long orderId, String orderNumber, Storefront storefront, String customerEmail,
                                String customerName, long amountMinor, String currency, boolean full) {
    }

    public record OrderCancelled(Long orderId, String orderNumber, Storefront storefront, String customerEmail,
                                 String customerName, String reason) {
    }

    public record CodOrderConfirmed(Long orderId, String orderNumber, Storefront storefront, String customerEmail,
                                    String customerName, long totalMinor, String currency) {
    }

    public record ShipmentDispatched(Long orderId, String orderNumber, Storefront storefront, String customerEmail,
                                     String customerName, String carrier, String trackingNumber, String trackingUrl) {
    }
}
