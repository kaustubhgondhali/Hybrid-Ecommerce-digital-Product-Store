package com.hybrid.platform.commerce.coupon;

import com.hybrid.platform.commerce.coupon.CouponRepositories.CouponRepository;
import com.hybrid.platform.commerce.coupon.CouponRepositories.RedemptionRepository;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.audit.AuditService;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.security.AdminPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Coupon rules are evaluated only here; the browser only sends the code. */
@Service
public class CouponService {

    private static final Pattern CODE = Pattern.compile("^[A-Z0-9_-]{3,40}$");
    private static final Set<CouponRedemption.Status> COUNTED = EnumSet.of(CouponRedemption.Status.RESERVED, CouponRedemption.Status.USED);

    public record Applied(Long couponId, String code, long discountMinor, String description) {
    }

    public record CouponView(Long id, String code, String description, String discountType, long discountValue,
                             Long maxDiscountMinor, long minSubtotalMinor, Instant startsAt, Instant endsAt,
                             Integer usageLimit, int perCustomerLimit, boolean active, long timesUsed, Instant createdAt) {
    }

    public record CouponInput(String code, String description, String discountType, Long discountValue,
                              Long maxDiscountMinor, Long minSubtotalMinor, Instant startsAt, Instant endsAt,
                              Integer usageLimit, Integer perCustomerLimit, Boolean active) {
    }

    private final CouponRepository coupons;
    private final RedemptionRepository redemptions;
    private final AuditService audit;

    public CouponService(CouponRepository coupons, RedemptionRepository redemptions, AuditService audit) {
        this.coupons = coupons;
        this.redemptions = redemptions;
        this.audit = audit;
    }

    /** Validates a code for a cart subtotal and returns the discount; throws a customer-friendly error if not usable. */
    @Transactional(readOnly = true)
    public Applied evaluate(Storefront sf, Long customerId, String rawCode, long subtotalMinor) {
        Coupon c = coupons.findByStorefrontAndCode(sf, normalize(rawCode))
                .orElseThrow(() -> ApiException.rule("COUPON_INVALID", "This coupon code is not valid"));
        check(c, customerId, subtotalMinor);
        return new Applied(c.getId(), c.getCode(), c.discountFor(subtotalMinor), c.getDescription());
    }

    /** Locks the coupon row and records a reservation, so usage limits hold under concurrent checkouts. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Applied reserve(Storefront sf, Long customerId, Long orderId, String rawCode, long subtotalMinor) {
        Coupon found = coupons.findByStorefrontAndCode(sf, normalize(rawCode))
                .orElseThrow(() -> ApiException.rule("COUPON_INVALID", "This coupon code is not valid"));
        Coupon c = coupons.lockById(found.getId()).orElseThrow();
        check(c, customerId, subtotalMinor);
        long discount = c.discountFor(subtotalMinor);
        redemptions.save(new CouponRedemption(c.getId(), customerId, orderId, discount));
        return new Applied(c.getId(), c.getCode(), discount, c.getDescription());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markUsed(Long orderId) {
        redemptions.findByOrderId(orderId).filter(r -> r.getStatus() == CouponRedemption.Status.RESERVED)
                .ifPresent(r -> r.setStatus(CouponRedemption.Status.USED));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(Long orderId) {
        redemptions.findByOrderId(orderId).filter(r -> r.getStatus() != CouponRedemption.Status.RELEASED)
                .ifPresent(r -> r.setStatus(CouponRedemption.Status.RELEASED));
    }

    private void check(Coupon c, Long customerId, long subtotalMinor) {
        Instant now = Instant.now();
        if (!c.isActive() || (c.getStartsAt() != null && now.isBefore(c.getStartsAt()))) {
            throw ApiException.rule("COUPON_INVALID", "This coupon code is not valid");
        }
        if (c.getEndsAt() != null && now.isAfter(c.getEndsAt())) {
            throw ApiException.rule("COUPON_EXPIRED", "This coupon has expired");
        }
        if (subtotalMinor < c.getMinSubtotalMinor()) {
            throw ApiException.rule("COUPON_MIN_SUBTOTAL", "Add items worth \u20B9" + rupees(c.getMinSubtotalMinor() - subtotalMinor)
                    + " more to use this coupon");
        }
        if (c.getUsageLimit() != null && redemptions.countByCouponIdAndStatusIn(c.getId(), COUNTED) >= c.getUsageLimit()) {
            throw ApiException.rule("COUPON_EXHAUSTED", "This coupon has been fully redeemed");
        }
        if (customerId != null && c.getPerCustomerLimit() > 0
                && redemptions.countByCouponIdAndCustomerIdAndStatusIn(c.getId(), customerId, COUNTED) >= c.getPerCustomerLimit()) {
            throw ApiException.rule("COUPON_ALREADY_USED", "You have already used this coupon");
        }
    }

    // ------------------------------------------------------------------ admin

    @Transactional(readOnly = true)
    public List<CouponView> list(Storefront sf) {
        return coupons.findByStorefrontOrderByCreatedAtDesc(sf).stream().map(this::view).toList();
    }

    @Transactional
    public CouponView create(AdminPrincipal admin, CouponInput in) {
        String code = normalize(in.code());
        if (!CODE.matcher(code).matches()) {
            throw ApiException.badRequest("INVALID_COUPON_CODE", "Code must be 3-40 letters, digits, - or _");
        }
        if (coupons.existsByStorefrontAndCode(admin.storefront(), code)) {
            throw ApiException.conflict("COUPON_EXISTS", "A coupon with this code already exists");
        }
        if (in.discountType() == null || in.discountValue() == null) {
            throw ApiException.badRequest("FIELD_REQUIRED", "Discount type and value are required");
        }
        Coupon c = new Coupon(admin.storefront(), code);
        apply(c, in);
        coupons.save(c);
        audit.record(admin, "COUPON_CREATED", "Coupon", c.getId(), code);
        return view(c);
    }

    @Transactional
    public CouponView update(AdminPrincipal admin, Long id, CouponInput in) {
        Coupon c = coupons.findByIdAndStorefront(id, admin.storefront())
                .orElseThrow(() -> ApiException.notFound("COUPON_NOT_FOUND", "Coupon not found"));
        apply(c, in);
        audit.record(admin, "COUPON_UPDATED", "Coupon", id, c.getCode());
        return view(c);
    }

    private static void apply(Coupon c, CouponInput in) {
        if (in.description() != null) c.setDescription(in.description().isBlank() ? null : limit(in.description(), 200));
        if (in.discountType() != null) {
            try {
                c.setDiscountType(Coupon.Type.valueOf(in.discountType().trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("INVALID_DISCOUNT_TYPE", "Discount type must be PERCENT or FIXED");
            }
        }
        if (in.discountValue() != null) c.setDiscountValue(in.discountValue());
        if (c.getDiscountType() == Coupon.Type.PERCENT && (c.getDiscountValue() <= 0 || c.getDiscountValue() > 10_000)) {
            throw ApiException.badRequest("INVALID_DISCOUNT", "Percentage must be between 0.01% and 100%");
        }
        if (c.getDiscountType() == Coupon.Type.FIXED && (c.getDiscountValue() <= 0 || c.getDiscountValue() > 100_000_000L)) {
            throw ApiException.badRequest("INVALID_DISCOUNT", "Discount amount is out of range");
        }
        if (in.maxDiscountMinor() != null) c.setMaxDiscountMinor(in.maxDiscountMinor() <= 0 ? null : in.maxDiscountMinor());
        if (in.minSubtotalMinor() != null) c.setMinSubtotalMinor(Math.max(0, in.minSubtotalMinor()));
        if (in.startsAt() != null) c.setStartsAt(in.startsAt());
        if (in.endsAt() != null) c.setEndsAt(in.endsAt());
        if (c.getStartsAt() != null && c.getEndsAt() != null && !c.getEndsAt().isAfter(c.getStartsAt())) {
            throw ApiException.badRequest("INVALID_DATES", "End date must be after the start date");
        }
        if (in.usageLimit() != null) c.setUsageLimit(in.usageLimit() <= 0 ? null : in.usageLimit());
        if (in.perCustomerLimit() != null) c.setPerCustomerLimit(Math.max(0, in.perCustomerLimit()));
        if (in.active() != null) c.setActive(in.active());
    }

    private CouponView view(Coupon c) {
        return new CouponView(c.getId(), c.getCode(), c.getDescription(), c.getDiscountType().name(), c.getDiscountValue(),
                c.getMaxDiscountMinor(), c.getMinSubtotalMinor(), c.getStartsAt(), c.getEndsAt(), c.getUsageLimit(),
                c.getPerCustomerLimit(), c.isActive(), redemptions.countByCouponIdAndStatusIn(c.getId(), COUNTED),
                c.getCreatedAt());
    }

    static String normalize(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    private static String limit(String v, int max) {
        String t = v.trim();
        if (t.length() > max) throw ApiException.badRequest("VALUE_TOO_LONG", "Value must be at most " + max + " characters");
        return t;
    }

    private static String rupees(long minor) {
        return minor % 100 == 0 ? String.valueOf(minor / 100) : String.format(Locale.ROOT, "%.2f", minor / 100.0);
    }
}
