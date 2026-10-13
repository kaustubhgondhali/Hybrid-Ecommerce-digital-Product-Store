package com.hybrid.platform.crm;

import com.hybrid.platform.crm.CrmSegments.Segment;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CrmSegmentsTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    /** Small builder so each test only states what matters. */
    private static final class Snap {
        boolean subscribed;
        boolean signedUpHere = true;
        Instant signedUpAt = NOW.minus(Duration.ofDays(100));
        long orders;
        Instant lastOrderAt;
        long cartItems;
        Instant cartUpdatedAt;
        long wishlist;
        Instant wishlistAt;
        long clicks;
        Instant lastClickAt;
        long conversions;
        Instant lastConversionAt;

        CustomerSnapshot build() {
            return new CustomerSnapshot(1L, "a@test.local", "Asha Rao", null, true, signedUpAt, signedUpHere,
                    orders, orders * 1000, lastOrderAt, lastOrderAt, cartItems, cartUpdatedAt, wishlist, wishlistAt,
                    clicks, lastClickAt, conversions, conversions * 50, conversions * 2000, lastConversionAt, 0, subscribed);
        }
    }

    private static Instant ago(long hours) {
        return NOW.minus(Duration.ofHours(hours));
    }

    @Test
    void freshSignupIsANewLeadAndCold() {
        Snap s = new Snap();
        s.signedUpAt = ago(5);
        CustomerSnapshot c = s.build();
        assertThat(CrmSegments.segments(c, NOW)).containsExactlyInAnyOrder(Segment.ALL, Segment.NEW_SIGNUPS);
        assertThat(CrmSegments.temperature(CrmSegments.intentScore(c, NOW))).isEqualTo("COLD");
        assertThat(CrmSegments.nextAction(c, CrmSegments.segments(c, NOW), NOW).code()).isEqualTo("WELCOME");
    }

    @Test
    void cartIdleForOverAnHourIsAbandonedAndHot() {
        Snap s = new Snap();
        s.subscribed = true;
        s.cartItems = 3;
        s.cartUpdatedAt = ago(3);
        CustomerSnapshot c = s.build();
        Set<Segment> segs = CrmSegments.segments(c, NOW);
        assertThat(segs).contains(Segment.ABANDONED_CART, Segment.HOT_LEADS, Segment.SUBSCRIBERS).doesNotContain(Segment.NEW_SIGNUPS);
        assertThat(CrmSegments.intentScore(c, NOW)).isGreaterThanOrEqualTo(CrmSegments.HOT);
        assertThat(CrmSegments.nextAction(c, segs, NOW).text()).contains("3 items");
    }

    @Test
    void cartIsNotAbandonedWhileFreshOrAfterAPartnerClick() {
        Snap fresh = new Snap();
        fresh.cartItems = 1;
        fresh.cartUpdatedAt = NOW.minus(Duration.ofMinutes(20));
        assertThat(CrmSegments.segments(fresh.build(), NOW)).doesNotContain(Segment.ABANDONED_CART);

        Snap clicked = new Snap();
        clicked.cartItems = 1;
        clicked.cartUpdatedAt = ago(5);
        clicked.clicks = 1;
        clicked.lastClickAt = ago(4);
        assertThat(CrmSegments.segments(clicked.build(), NOW)).doesNotContain(Segment.ABANDONED_CART).contains(Segment.CLICKED_NO_SALE);
    }

    @Test
    void partnerSalesCountAsPurchases() {
        Snap s = new Snap();
        s.clicks = 4;
        s.lastClickAt = ago(24 * 10);
        s.conversions = 3;
        s.lastConversionAt = ago(24 * 9);
        Set<Segment> segs = CrmSegments.segments(s.build(), NOW);
        assertThat(segs).contains(Segment.REPEAT, Segment.VIP).doesNotContain(Segment.CLICKED_NO_SALE, Segment.FIRST_TIME);
        assertThat(CrmSegments.nextAction(s.build(), segs, NOW).code()).isEqualTo("REWARD_VIP");
    }

    @Test
    void lastPurchaseAgeDecidesAtRiskAndLapsed() {
        Snap risk = new Snap();
        risk.orders = 1;
        risk.lastOrderAt = NOW.minus(Duration.ofDays(75));
        Set<Segment> r = CrmSegments.segments(risk.build(), NOW);
        assertThat(r).contains(Segment.FIRST_TIME, Segment.AT_RISK).doesNotContain(Segment.LAPSED);
        assertThat(CrmSegments.nextAction(risk.build(), r, NOW).code()).isEqualTo("WIN_BACK");

        Snap lapsed = new Snap();
        lapsed.orders = 2;
        lapsed.lastOrderAt = NOW.minus(Duration.ofDays(200));
        assertThat(CrmSegments.segments(lapsed.build(), NOW)).contains(Segment.REPEAT, Segment.LAPSED).doesNotContain(Segment.AT_RISK);
    }

    @Test
    void scoreIsCappedAt100() {
        Snap s = new Snap();
        s.cartItems = 9;
        s.cartUpdatedAt = ago(2);
        s.wishlist = 9;
        s.wishlistAt = ago(1);
        s.clicks = 5;
        s.lastClickAt = ago(1);
        s.orders = 1;
        s.lastOrderAt = ago(24 * 20);
        assertThat(CrmSegments.intentScore(s.build(), NOW)).isEqualTo(100);
    }

    @Test
    void whatsappNumbersDefaultToIndia() {
        assertThat(CrmService.whatsapp("98765 43210")).isEqualTo("919876543210");
        assertThat(CrmService.whatsapp("09876543210")).isEqualTo("919876543210");
        assertThat(CrmService.whatsapp("+44 7700 900123")).isEqualTo("447700900123");
        assertThat(CrmService.whatsapp("123")).isNull();
        assertThat(CrmService.whatsapp(null)).isNull();
    }
}
