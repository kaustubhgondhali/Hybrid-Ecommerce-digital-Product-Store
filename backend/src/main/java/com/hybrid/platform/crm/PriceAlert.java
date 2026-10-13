package com.hybrid.platform.crm;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;

/** "Email me when this drops below ₹X", requested by a visitor and confirmed from their inbox. Sent once. */
@Entity
@Table(name = "price_alerts")
public class PriceAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Storefront storefront;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(nullable = false, updatable = false)
    private String email;

    @Column(name = "target_minor", nullable = false)
    private long targetMinor;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "notified_at")
    private Instant notifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PriceAlert() {
    }

    public PriceAlert(Storefront storefront, Long productId, String email) {
        this.storefront = storefront;
        this.productId = productId;
        this.email = email;
        this.createdAt = Instant.now();
    }

    public boolean isWaiting() {
        return confirmedAt != null && notifiedAt == null;
    }

    public Long getId() { return id; }
    public Storefront getStorefront() { return storefront; }
    public Long getProductId() { return productId; }
    public String getEmail() { return email; }
    public long getTargetMinor() { return targetMinor; }
    public void setTargetMinor(long targetMinor) { this.targetMinor = targetMinor; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Instant confirmedAt) { this.confirmedAt = confirmedAt; }
    public Instant getNotifiedAt() { return notifiedAt; }
    public void setNotifiedAt(Instant notifiedAt) { this.notifiedAt = notifiedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
