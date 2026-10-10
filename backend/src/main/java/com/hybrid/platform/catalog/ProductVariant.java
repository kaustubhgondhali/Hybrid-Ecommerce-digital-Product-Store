package com.hybrid.platform.catalog;

import jakarta.persistence.*;

@Entity
@Table(name = "product_variants")
public class ProductVariant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    private Product product;

    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(name = "price_minor")
    private Long priceMinor;

    @Column(name = "compare_at_minor")
    private Long compareAtMinor;

    @Column(name = "weight_grams")
    private Integer weightGrams;

    @Column(name = "is_default", nullable = false)
    private boolean defaultVariant;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected ProductVariant() {
    }

    public ProductVariant(String name, boolean defaultVariant, int sortOrder) {
        this.name = name;
        this.defaultVariant = defaultVariant;
        this.sortOrder = sortOrder;
    }

    void attach(Product product) {
        this.product = product;
    }

    public Long getId() { return id; }
    public Product getProduct() { return product; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku == null || sku.isBlank() ? null : sku.trim(); }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getPriceMinor() { return priceMinor; }
    public void setPriceMinor(Long priceMinor) { this.priceMinor = priceMinor; }
    public Long getCompareAtMinor() { return compareAtMinor; }
    public void setCompareAtMinor(Long compareAtMinor) { this.compareAtMinor = compareAtMinor; }
    public Integer getWeightGrams() { return weightGrams; }
    public void setWeightGrams(Integer weightGrams) { this.weightGrams = weightGrams; }
    public boolean isDefaultVariant() { return defaultVariant; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
