package com.hybrid.platform.delivery;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

@Entity
@Table(name = "delivery_policies")
public class DeliveryPolicy {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Storefront storefront;

    /** null = unlimited */
    @Column(name = "download_limit")
    private Integer downloadLimit;

    /** null = lifetime access */
    @Column(name = "access_days")
    private Integer accessDays;

    protected DeliveryPolicy() {
    }

    public DeliveryPolicy(Long productId, Storefront storefront) {
        this.productId = productId;
        this.storefront = storefront;
        this.downloadLimit = 5;
    }

    public Long getProductId() { return productId; }
    public Storefront getStorefront() { return storefront; }
    public Integer getDownloadLimit() { return downloadLimit; }
    public void setDownloadLimit(Integer downloadLimit) { this.downloadLimit = downloadLimit; }
    public Integer getAccessDays() { return accessDays; }
    public void setAccessDays(Integer accessDays) { this.accessDays = accessDays; }
}
