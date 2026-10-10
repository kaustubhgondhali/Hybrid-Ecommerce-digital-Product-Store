package com.hybrid.platform.commerce.cart;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Entity
@Table(name = "carts")
public class Cart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Storefront storefront;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<CartItem> items = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Cart() {
    }

    public Cart(Long customerId, Storefront storefront) {
        this.customerId = customerId;
        this.storefront = storefront;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public Optional<CartItem> findByVariant(Long variantId) {
        return items.stream().filter(i -> i.getVariantId().equals(variantId)).findFirst();
    }

    public Optional<CartItem> findItem(Long itemId) {
        return items.stream().filter(i -> i.getId().equals(itemId)).findFirst();
    }

    public void addItem(Long productId, Long variantId, int quantity) {
        items.add(new CartItem(this, productId, variantId, quantity));
        touch();
    }

    public void removeItem(CartItem item) {
        items.remove(item);
        touch();
    }

    public void clear() {
        items.clear();
        touch();
    }

    public void touch() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getCustomerId() { return customerId; }
    public Storefront getStorefront() { return storefront; }
    public List<CartItem> getItems() { return items; }
}
