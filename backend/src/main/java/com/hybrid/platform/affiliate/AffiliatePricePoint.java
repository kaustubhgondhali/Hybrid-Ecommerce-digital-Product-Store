package com.hybrid.platform.affiliate;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;

/** One recorded partner price, written whenever an offer's price changes. Powers "lowest in 30 days" and price drops. */
@Entity
@Table(name = "affiliate_price_history")
public class AffiliatePricePoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Storefront storefront;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "link_id", nullable = false, updatable = false)
    private Long linkId;

    @Column(name = "price_minor", nullable = false, updatable = false)
    private long priceMinor;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    protected AffiliatePricePoint() {
    }

    public AffiliatePricePoint(Storefront storefront, Long productId, Long linkId, long priceMinor, Instant recordedAt) {
        this.storefront = storefront;
        this.productId = productId;
        this.linkId = linkId;
        this.priceMinor = priceMinor;
        this.recordedAt = recordedAt;
    }

    public Long getId() { return id; }
    public Storefront getStorefront() { return storefront; }
    public Long getProductId() { return productId; }
    public Long getLinkId() { return linkId; }
    public long getPriceMinor() { return priceMinor; }
    public Instant getRecordedAt() { return recordedAt; }
}
