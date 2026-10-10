package com.hybrid.platform.commerce.cart;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "cart_items")
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false, updatable = false)
    private Cart cart;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "variant_id", nullable = false, updatable = false)
    private Long variantId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    protected CartItem() {
    }

    CartItem(Cart cart, Long productId, Long variantId, int quantity) {
        this.cart = cart;
        this.productId = productId;
        this.variantId = variantId;
        this.quantity = quantity;
        this.addedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getProductId() { return productId; }
    public Long getVariantId() { return variantId; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
}
