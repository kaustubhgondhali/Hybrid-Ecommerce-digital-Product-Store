package com.hybrid.platform.delivery;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "entitlements")
public class Entitlement {

    public enum Status { ACTIVE, REVOKED }

    public enum Source { PURCHASE, ADMIN_GRANT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Storefront storefront;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "product_title", nullable = false)
    private String productTitle;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "order_line_id")
    private Long orderLineId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoke_reason")
    private String revokeReason;

    @Column(name = "download_limit")
    private Integer downloadLimit;

    @Column(name = "downloads_used", nullable = false)
    private int downloadsUsed;

    @Version
    private long version;

    protected Entitlement() {
    }

    public Entitlement(Storefront storefront, Long customerId, Long productId, String productTitle, Long orderId,
                       Long orderLineId, Source source, Integer downloadLimit, Instant expiresAt) {
        this.storefront = storefront;
        this.customerId = customerId;
        this.productId = productId;
        this.productTitle = productTitle;
        this.orderId = orderId;
        this.orderLineId = orderLineId;
        this.source = source;
        this.downloadLimit = downloadLimit;
        this.expiresAt = expiresAt;
        this.status = Status.ACTIVE;
        this.grantedAt = Instant.now();
    }

    public boolean isUsable(Instant now) {
        return status == Status.ACTIVE && (expiresAt == null || expiresAt.isAfter(now));
    }

    public boolean hasDownloadsLeft() {
        return downloadLimit == null || downloadsUsed < downloadLimit;
    }

    public void countDownload() {
        downloadsUsed++;
    }

    public void revoke(String reason) {
        status = Status.REVOKED;
        revokedAt = Instant.now();
        revokeReason = reason;
    }

    public Long getId() { return id; }
    public Storefront getStorefront() { return storefront; }
    public Long getCustomerId() { return customerId; }
    public Long getProductId() { return productId; }
    public String getProductTitle() { return productTitle; }
    public Long getOrderId() { return orderId; }
    public Source getSource() { return source; }
    public Status getStatus() { return status; }
    public Instant getGrantedAt() { return grantedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public String getRevokeReason() { return revokeReason; }
    public Integer getDownloadLimit() { return downloadLimit; }
    public int getDownloadsUsed() { return downloadsUsed; }
}
