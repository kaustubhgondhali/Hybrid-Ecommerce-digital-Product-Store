package com.hybrid.platform.commerce.inventory;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

@Entity
@Table(name = "inventory_items")
public class InventoryItem {

    @Id
    @Column(name = "variant_id")
    private Long variantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Storefront storefront;

    @Column(name = "on_hand", nullable = false)
    private int onHand;

    @Column(nullable = false)
    private int reserved;

    @Column(name = "low_stock_threshold", nullable = false)
    private int lowStockThreshold = 5;

    @Version
    private long version;

    protected InventoryItem() {
    }

    public InventoryItem(Long variantId, Storefront storefront) {
        this.variantId = variantId;
        this.storefront = storefront;
    }

    public int available() {
        return onHand - reserved;
    }

    public Long getVariantId() { return variantId; }
    public Storefront getStorefront() { return storefront; }
    public int getOnHand() { return onHand; }
    public void setOnHand(int onHand) { this.onHand = onHand; }
    public int getReserved() { return reserved; }
    public int getLowStockThreshold() { return lowStockThreshold; }
    public void setLowStockThreshold(int lowStockThreshold) { this.lowStockThreshold = lowStockThreshold; }
}
