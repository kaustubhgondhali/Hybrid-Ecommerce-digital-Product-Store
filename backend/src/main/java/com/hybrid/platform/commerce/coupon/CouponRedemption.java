package com.hybrid.platform.commerce.coupon;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "coupon_redemptions")
public class CouponRedemption {

    /** RESERVED while the order awaits payment, USED once paid, RELEASED if the order expires/cancels/refunds. */
    public enum Status { RESERVED, USED, RELEASED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "coupon_id", nullable = false, updatable = false)
    private Long couponId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "discount_minor", nullable = false, updatable = false)
    private long discountMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CouponRedemption() {
    }

    public CouponRedemption(Long couponId, Long customerId, Long orderId, long discountMinor) {
        this.couponId = couponId;
        this.customerId = customerId;
        this.orderId = orderId;
        this.discountMinor = discountMinor;
        this.status = Status.RESERVED;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getCouponId() { return couponId; }
    public Long getCustomerId() { return customerId; }
    public Long getOrderId() { return orderId; }
    public long getDiscountMinor() { return discountMinor; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
}
