package com.hybrid.platform.offers;

import com.hybrid.platform.affiliate.AffiliateMerchant;
import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;

/** A coupon code or deal at a partner store ("Flat 20% off with code SAVE20"), shown with a "go to store" button. */
@Entity
@Table(name = "store_offers")
public class StoreOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Storefront storefront;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id")
    private AffiliateMerchant merchant;

    @Column(name = "product_id")
    private Long productId;

    @Column(nullable = false)
    private String title;

    private String description;

    /** Coupon code to copy; null for deals that need no code. */
    private String code;

    @Column(nullable = false)
    private String url;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(nullable = false)
    private boolean active = true;

    /** When the team last checked that the code works ("Verified today"). */
    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(nullable = false)
    private long clicks;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StoreOffer() {
    }

    public StoreOffer(Storefront storefront) {
        this.storefront = storefront;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /** Shown to shoppers: switched on, store enabled, started and not expired. */
    public boolean isLive(Instant now) {
        return active && merchant.isActive() && (startsAt == null || !startsAt.isAfter(now)) && (endsAt == null || endsAt.isAfter(now));
    }

    public Long getId() { return id; }
    public Storefront getStorefront() { return storefront; }
    public AffiliateMerchant getMerchant() { return merchant; }
    public void setMerchant(AffiliateMerchant merchant) { this.merchant = merchant; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public Instant getStartsAt() { return startsAt; }
    public void setStartsAt(Instant startsAt) { this.startsAt = startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public void setEndsAt(Instant endsAt) { this.endsAt = endsAt; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Instant verifiedAt) { this.verifiedAt = verifiedAt; }
    public long getClicks() { return clicks; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
