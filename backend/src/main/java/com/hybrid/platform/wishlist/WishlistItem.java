package com.hybrid.platform.wishlist;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "wishlist_items")
public class WishlistItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Storefront storefront;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WishlistItem() {
    }

    public WishlistItem(Long customerId, Storefront storefront, Long productId) {
        this.customerId = customerId;
        this.storefront = storefront;
        this.productId = productId;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getCustomerId() { return customerId; }
    public Storefront getStorefront() { return storefront; }
    public Long getProductId() { return productId; }
    public Instant getCreatedAt() { return createdAt; }
}
