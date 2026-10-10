package com.hybrid.platform.commerce.coupon;

import com.hybrid.platform.common.Storefront;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CouponDiscountTest {

    private static Coupon coupon(Coupon.Type type, long value, Long cap) {
        Coupon c = new Coupon(Storefront.SHOP, "SAVE");
        c.setDiscountType(type);
        c.setDiscountValue(value);
        c.setMaxDiscountMinor(cap);
        return c;
    }

    @Test
    void percentageUsesBasisPoints() {
        assertThat(coupon(Coupon.Type.PERCENT, 1_000, null).discountFor(50_000)).isEqualTo(5_000);
        assertThat(coupon(Coupon.Type.PERCENT, 1_250, null).discountFor(999)).isEqualTo(125);
    }

    @Test
    void percentageIsCappedByMaxDiscount() {
        assertThat(coupon(Coupon.Type.PERCENT, 5_000, 3_000L).discountFor(50_000)).isEqualTo(3_000);
    }

    @Test
    void fixedDiscountNeverExceedsSubtotal() {
        assertThat(coupon(Coupon.Type.FIXED, 70_000, null).discountFor(50_000)).isEqualTo(50_000);
        assertThat(coupon(Coupon.Type.FIXED, 10_000, null).discountFor(50_000)).isEqualTo(10_000);
    }

    @Test
    void codesAreNormalisedToUpperCase() {
        assertThat(CouponService.normalize("  diwali10 ")).isEqualTo("DIWALI10");
        assertThat(CouponService.normalize(null)).isEmpty();
    }
}
