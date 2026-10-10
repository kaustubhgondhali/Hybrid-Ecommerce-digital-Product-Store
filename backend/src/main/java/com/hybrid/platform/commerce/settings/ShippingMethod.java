package com.hybrid.platform.commerce.settings;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

@Entity
@Table(name = "shipping_methods")
public class ShippingMethod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Storefront storefront;

    @Column(nullable = false)
    private String name;

    @Column(name = "rate_minor", nullable = false)
    private long rateMinor;

    @Column(name = "free_above_minor")
    private Long freeAboveMinor;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected ShippingMethod() {
    }

    public ShippingMethod(Storefront storefront, String name, long rateMinor, Long freeAboveMinor) {
        this.storefront = storefront;
        this.name = name;
        this.rateMinor = rateMinor;
        this.freeAboveMinor = freeAboveMinor;
    }

    public long rateFor(long subtotalMinor) {
        return freeAboveMinor != null && subtotalMinor >= freeAboveMinor ? 0 : rateMinor;
    }

    public Long getId() { return id; }
    public Storefront getStorefront() { return storefront; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public long getRateMinor() { return rateMinor; }
    public void setRateMinor(long rateMinor) { this.rateMinor = rateMinor; }
    public Long getFreeAboveMinor() { return freeAboveMinor; }
    public void setFreeAboveMinor(Long freeAboveMinor) { this.freeAboveMinor = freeAboveMinor; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
