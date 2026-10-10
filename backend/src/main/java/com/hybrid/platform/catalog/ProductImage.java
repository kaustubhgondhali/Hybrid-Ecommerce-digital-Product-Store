package com.hybrid.platform.catalog;

import jakarta.persistence.*;

@Entity
@Table(name = "product_images")
public class ProductImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(name = "thumb_key")
    private String thumbKey;

    @Column(name = "large_key")
    private String largeKey;

    private Integer width;

    private Integer height;

    @Column(name = "alt_text")
    private String altText;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected ProductImage() {
    }

    public ProductImage(String storageKey, String altText, int sortOrder) {
        this.storageKey = storageKey;
        this.altText = altText;
        this.sortOrder = sortOrder;
    }

    void attach(Product product) {
        this.product = product;
    }

    public String url() {
        return "/media/" + storageKey;
    }

    /** Small version for product grids; falls back to the original for images uploaded before thumbnails existed. */
    public String thumbUrl() {
        return thumbKey == null ? url() : "/media/" + thumbKey;
    }

    /** Product-page size (max 1200 px wide); falls back to the original when it is already small or predates renditions. */
    public String largeUrl() {
        return largeKey == null ? url() : "/media/" + largeKey;
    }

    public void setRenditions(String thumbKey, String largeKey, Integer width, Integer height) {
        this.thumbKey = thumbKey;
        this.largeKey = largeKey;
        this.width = width;
        this.height = height;
    }

    public Long getId() { return id; }
    public Product getProduct() { return product; }
    public String getStorageKey() { return storageKey; }
    public String getThumbKey() { return thumbKey; }
    public String getLargeKey() { return largeKey; }
    public Integer getWidth() { return width; }
    public Integer getHeight() { return height; }
    public String getAltText() { return altText; }
    public void setAltText(String altText) { this.altText = altText; }
    public int getSortOrder() { return sortOrder; }
}
