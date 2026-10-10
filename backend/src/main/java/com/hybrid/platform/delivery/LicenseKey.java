package com.hybrid.platform.delivery;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "license_keys")
public class LicenseKey {

    public enum Status { AVAILABLE, ASSIGNED, REVOKED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Storefront storefront;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "key_enc", nullable = false)
    private String keyEnc;

    @Column(nullable = false)
    private String fingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "entitlement_id")
    private Long entitlementId;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LicenseKey() {
    }

    public LicenseKey(Storefront storefront, Long productId, String keyEnc, String fingerprint) {
        this.storefront = storefront;
        this.productId = productId;
        this.keyEnc = keyEnc;
        this.fingerprint = fingerprint;
        this.status = Status.AVAILABLE;
        this.createdAt = Instant.now();
    }

    public void assign(Long entitlementId) {
        this.entitlementId = entitlementId;
        this.status = Status.ASSIGNED;
        this.assignedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getKeyEnc() { return keyEnc; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Long getEntitlementId() { return entitlementId; }
}
