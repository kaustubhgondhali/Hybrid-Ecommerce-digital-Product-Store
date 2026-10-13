package com.hybrid.platform.crm;

import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Everything one storefront knows about a customer, aggregated by {@link CrmQueries}. "Purchases" count both
 * direct orders and partner sales reported for the customer's affiliate clicks (affiliate-only stores have no orders).
 */
public record CustomerSnapshot(
        Long id, String email, String fullName, String phone, boolean active,
        Instant signedUpAt, boolean signedUpHere,
        long orders, long revenueMinor, Instant firstOrderAt, Instant lastOrderAt,
        long cartItems, Instant cartUpdatedAt,
        long wishlistItems, Instant wishlistUpdatedAt,
        long clicks, Instant lastClickAt,
        long conversions, long commissionMinor, long partnerSalesMinor, Instant lastConversionAt,
        long reviews,
        boolean subscribed) {

    public long purchases() {
        return orders + conversions;
    }

    /** What the customer spent with us directly plus at partner stores through our links. */
    public long spendMinor() {
        return revenueMinor + partnerSalesMinor;
    }

    public Instant lastPurchaseAt() {
        return latest(lastOrderAt, lastConversionAt);
    }

    public Instant lastActivityAt() {
        return latest(signedUpHere ? signedUpAt : null, lastOrderAt, cartUpdatedAt, wishlistUpdatedAt, lastClickAt, lastConversionAt);
    }

    /** True when the cart was touched after the customer's last purchase and last partner click. */
    public boolean cartUntouchedSinceSaving() {
        return cartItems > 0 && cartUpdatedAt != null
                && (lastOrderAt == null || lastOrderAt.isBefore(cartUpdatedAt))
                && (lastClickAt == null || lastClickAt.isBefore(cartUpdatedAt));
    }

    public String firstName() {
        String n = fullName == null ? "" : fullName.trim();
        int space = n.indexOf(' ');
        return n.isEmpty() ? "there" : space > 0 ? n.substring(0, space) : n;
    }

    static Instant latest(Instant... values) {
        return Stream.of(values).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
    }
}
