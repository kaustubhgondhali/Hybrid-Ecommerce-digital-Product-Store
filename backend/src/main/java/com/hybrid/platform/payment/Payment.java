package com.hybrid.platform.payment;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "payments")
public class Payment {

    public enum Status { CREATED, SUCCEEDED, FAILED, CANCELLED, PARTIALLY_REFUNDED, REFUNDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Storefront storefront;

    @Column(nullable = false, updatable = false)
    private String provider;

    @Column(name = "provider_order_id", nullable = false)
    private String providerOrderId;

    @Column(name = "provider_payment_id")
    private String providerPaymentId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "refunded_minor", nullable = false)
    private long refundedMinor;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Payment() {
    }

    public Payment(Long orderId, Storefront storefront, String provider, String providerOrderId, long amountMinor, String currency) {
        this.orderId = orderId;
        this.storefront = storefront;
        this.provider = provider;
        this.providerOrderId = providerOrderId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = Status.CREATED;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public Storefront getStorefront() { return storefront; }
    public String getProvider() { return provider; }
    public String getProviderOrderId() { return providerOrderId; }
    public String getProviderPaymentId() { return providerPaymentId; }
    public void setProviderPaymentId(String providerPaymentId) { this.providerPaymentId = providerPaymentId; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public long getRefundedMinor() { return refundedMinor; }
    public void addRefund(long amount) { this.refundedMinor += amount; }
    public Instant getCreatedAt() { return createdAt; }
}
