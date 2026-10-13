package com.hybrid.platform.affiliate;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "affiliate_links")
public class AffiliateLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Storefront storefront;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id")
    private AffiliateMerchant merchant;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(name = "destination_url", nullable = false, length = 2048)
    private String destinationUrl;

    @Column(name = "display_price_minor")
    private Long displayPriceMinor;

    @Column(name = "price_checked_at")
    private Instant priceCheckedAt;

    /** Original / maximum retail price at the partner, to show the discount; null when unknown. */
    @Column(name = "list_price_minor")
    private Long listPriceMinor;

    /** Result of the nightly link check: null (not checked yet), OK, UNKNOWN or BROKEN (hidden from shoppers). */
    @Column(name = "health_status")
    private String healthStatus;

    @Column(name = "health_checked_at")
    private Instant healthCheckedAt;

    @Column(name = "health_failures", nullable = false)
    private int healthFailures;

    @Column(name = "health_detail")
    private String healthDetail;

    private String disclosure;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AffiliateLink() {
    }

    public AffiliateLink(Storefront storefront, Long productId, String code) {
        this.storefront = storefront;
        this.productId = productId;
        this.code = code;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Storefront getStorefront() { return storefront; }
    public Long getProductId() { return productId; }
    public AffiliateMerchant getMerchant() { return merchant; }
    public void setMerchant(AffiliateMerchant merchant) { this.merchant = merchant; }
    public String getCode() { return code; }
    public String getDestinationUrl() { return destinationUrl; }
    public void setDestinationUrl(String destinationUrl) { this.destinationUrl = destinationUrl; }
    public Long getDisplayPriceMinor() { return displayPriceMinor; }
    public void setDisplayPriceMinor(Long displayPriceMinor) { this.displayPriceMinor = displayPriceMinor; }
    public Instant getPriceCheckedAt() { return priceCheckedAt; }
    public void setPriceCheckedAt(Instant priceCheckedAt) { this.priceCheckedAt = priceCheckedAt; }
    public Long getListPriceMinor() { return listPriceMinor; }
    public void setListPriceMinor(Long listPriceMinor) { this.listPriceMinor = listPriceMinor; }
    public String getHealthStatus() { return healthStatus; }
    public Instant getHealthCheckedAt() { return healthCheckedAt; }
    public int getHealthFailures() { return healthFailures; }
    public String getHealthDetail() { return healthDetail; }
    public boolean isBroken() { return "BROKEN".equals(healthStatus); }

    public void recordHealth(String status, int failures, String detail, Instant at) {
        this.healthStatus = status;
        this.healthFailures = failures;
        this.healthDetail = detail == null ? null : detail.substring(0, Math.min(detail.length(), 200));
        this.healthCheckedAt = at;
    }

    /** A new destination starts unchecked. */
    public void resetHealth() {
        recordHealth(null, 0, null, null);
    }
    public String getDisclosure() { return disclosure; }
    public void setDisclosure(String disclosure) { this.disclosure = disclosure; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getUpdatedAt() { return updatedAt; }
}
