package com.hybrid.platform.commerce.coupon;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "coupons")
public class Coupon {

    /** PERCENT values are basis points (1000 = 10%); FIXED values are paise. */
    public enum Type { PERCENT, FIXED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Storefront storefront;

    @Column(nullable = false)
    private String code;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false)
    private Type discountType;

    @Column(name = "discount_value", nullable = false)
    private long discountValue;

    @Column(name = "max_discount_minor")
    private Long maxDiscountMinor;

    @Column(name = "min_subtotal_minor", nullable = false)
    private long minSubtotalMinor;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "usage_limit")
    private Integer usageLimit;

    @Column(name = "per_customer_limit", nullable = false)
    private int perCustomerLimit = 1;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Coupon() {
    }

    public Coupon(Storefront storefront, String code) {
        this.storefront = storefront;
        this.code = code;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /** Discount for a subtotal, never more than the subtotal. */
    public long discountFor(long subtotalMinor) {
        long d = discountType == Type.PERCENT ? Math.round(subtotalMinor * discountValue / 10_000d) : discountValue;
        if (maxDiscountMinor != null) d = Math.min(d, maxDiscountMinor);
        return Math.max(0, Math.min(d, subtotalMinor));
    }

    public Long getId() { return id; }
    public Storefront getStorefront() { return storefront; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Type getDiscountType() { return discountType; }
    public void setDiscountType(Type discountType) { this.discountType = discountType; }
    public long getDiscountValue() { return discountValue; }
    public void setDiscountValue(long discountValue) { this.discountValue = discountValue; }
    public Long getMaxDiscountMinor() { return maxDiscountMinor; }
    public void setMaxDiscountMinor(Long maxDiscountMinor) { this.maxDiscountMinor = maxDiscountMinor; }
    public long getMinSubtotalMinor() { return minSubtotalMinor; }
    public void setMinSubtotalMinor(long minSubtotalMinor) { this.minSubtotalMinor = minSubtotalMinor; }
    public Instant getStartsAt() { return startsAt; }
    public void setStartsAt(Instant startsAt) { this.startsAt = startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public void setEndsAt(Instant endsAt) { this.endsAt = endsAt; }
    public Integer getUsageLimit() { return usageLimit; }
    public void setUsageLimit(Integer usageLimit) { this.usageLimit = usageLimit; }
    public int getPerCustomerLimit() { return perCustomerLimit; }
    public void setPerCustomerLimit(int perCustomerLimit) { this.perCustomerLimit = perCustomerLimit; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
}
