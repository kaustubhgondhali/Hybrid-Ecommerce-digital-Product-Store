package com.hybrid.platform.crm;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * Plain-language customer segments, a 0-100 buying-intent score and the suggested next step for staff.
 * Pure functions of a {@link CustomerSnapshot}, so the rules are easy to test and to explain in the admin.
 */
public final class CrmSegments {

    static final Duration ABANDONED_AFTER = Duration.ofHours(1);
    static final int AT_RISK_DAYS = 60;
    static final int LAPSED_DAYS = 180;
    static final int HOT = 60;
    static final int WARM = 30;

    private CrmSegments() {
    }

    public enum Segment {
        ALL("All customers", "Everyone who signed up on this store or used it."),
        HOT_LEADS("Hot leads", "Buying-intent score of " + HOT + " or more: several items saved in the cart, recent wishlist adds or partner clicks."),
        ABANDONED_CART("Abandoned carts", "Items saved in the cart for over an hour, with no purchase or partner click since."),
        CLICKED_NO_SALE("Clicked, no sale", "Clicked through to a partner store, but no sale has been recorded yet."),
        WISHLIST("Wishlist, never bought", "Saved items to their wishlist but have not bought anything."),
        NEW_SIGNUPS("New sign-ups", "Signed up but have not saved, clicked or bought anything yet."),
        FIRST_TIME("First-time buyers", "Bought once. The best moment to win a second purchase."),
        REPEAT("Repeat customers", "Bought two or more times."),
        VIP("VIP", "Bought three or more times."),
        AT_RISK("At risk", "Bought before, but nothing in the last " + AT_RISK_DAYS + " to " + LAPSED_DAYS + " days."),
        LAPSED("Lapsed", "Last purchase was more than " + LAPSED_DAYS + " days ago."),
        SUBSCRIBERS("Email subscribers", "Agreed to receive marketing email from this store.");

        private final String label;
        private final String description;

        Segment(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String label() {
            return label;
        }

        public String description() {
            return description;
        }
    }

    public record NextAction(String code, String text) {
    }

    public static Set<Segment> segments(CustomerSnapshot c, Instant now) {
        Set<Segment> s = EnumSet.of(Segment.ALL);
        long purchases = c.purchases();
        if (c.subscribed()) s.add(Segment.SUBSCRIBERS);
        if (intentScore(c, now) >= HOT) s.add(Segment.HOT_LEADS);
        if (c.cartUntouchedSinceSaving() && !c.cartUpdatedAt().isAfter(now.minus(ABANDONED_AFTER))) s.add(Segment.ABANDONED_CART);
        if (c.clicks() > 0 && purchases == 0) s.add(Segment.CLICKED_NO_SALE);
        if (c.wishlistItems() > 0 && purchases == 0) s.add(Segment.WISHLIST);
        if (purchases == 0 && c.cartItems() == 0 && c.wishlistItems() == 0 && c.clicks() == 0) s.add(Segment.NEW_SIGNUPS);
        if (purchases == 1) s.add(Segment.FIRST_TIME);
        if (purchases >= 2) s.add(Segment.REPEAT);
        if (purchases >= 3) s.add(Segment.VIP);
        Instant last = c.lastPurchaseAt();
        if (purchases > 0 && last != null) {
            long days = Duration.between(last, now).toDays();
            if (days >= LAPSED_DAYS) s.add(Segment.LAPSED);
            else if (days >= AT_RISK_DAYS) s.add(Segment.AT_RISK);
        }
        return s;
    }

    /** 0-100: how likely the customer is to buy soon, from saved items, partner clicks and how recently they were active. */
    public static int intentScore(CustomerSnapshot c, Instant now) {
        int score = 0;
        if (c.cartItems() > 0) score += 30 + (int) Math.min(c.cartItems(), 5) * 4;
        if (c.wishlistItems() > 0) score += 10 + (int) Math.min(c.wishlistItems(), 5) * 2;
        if (c.lastClickAt() != null) score += within(c.lastClickAt(), now, 7) ? 20 : 5;
        Instant active = c.lastActivityAt();
        if (active != null) {
            if (within(active, now, 2)) score += 20;
            else if (within(active, now, 7)) score += 12;
            else if (within(active, now, 30)) score += 5;
        }
        if (c.purchases() > 0) score += 10;
        return Math.min(score, 100);
    }

    public static String temperature(int score) {
        return score >= HOT ? "HOT" : score >= WARM ? "WARM" : "COLD";
    }

    public static NextAction nextAction(CustomerSnapshot c, Set<Segment> s, Instant now) {
        if (s.contains(Segment.ABANDONED_CART)) {
            return new NextAction("REMIND_CART", "Remind them about the " + c.cartItems() + (c.cartItems() == 1 ? " item" : " items")
                    + " waiting in their cart.");
        }
        boolean recentClick = c.lastClickAt() != null && within(c.lastClickAt(), now, 14);
        if (s.contains(Segment.CLICKED_NO_SALE) && recentClick) {
            return new NextAction("FOLLOW_UP_CLICK", "Follow up on the deal they clicked: share a similar or cheaper offer.");
        }
        if (s.contains(Segment.AT_RISK)) {
            return new NextAction("WIN_BACK", "Win them back with a personal offer before they drift away.");
        }
        if (s.contains(Segment.WISHLIST)) {
            return new NextAction("WISHLIST_NUDGE", "Let them know when a wishlist item drops in price or is on offer.");
        }
        if (s.contains(Segment.FIRST_TIME)) {
            return new NextAction("SECOND_PURCHASE", "Thank them and suggest a related product for a second purchase.");
        }
        if (s.contains(Segment.NEW_SIGNUPS)) {
            return new NextAction("WELCOME", "Welcome them with your best deals or a first-order offer.");
        }
        if (s.contains(Segment.VIP)) {
            return new NextAction("REWARD_VIP", "Reward their loyalty with early access or an exclusive offer.");
        }
        if (s.contains(Segment.LAPSED)) {
            return new NextAction("REACTIVATE", "Try a win-back email with your strongest offer.");
        }
        if (s.contains(Segment.CLICKED_NO_SALE)) {
            return new NextAction("FOLLOW_UP_CLICK", "Share new deals in the category they clicked.");
        }
        return new NextAction("NEW_ARRIVALS", "Keep them engaged with new arrivals and offers.");
    }

    private static boolean within(Instant t, Instant now, int days) {
        return !t.isBefore(now.minus(Duration.ofDays(days)));
    }
}
