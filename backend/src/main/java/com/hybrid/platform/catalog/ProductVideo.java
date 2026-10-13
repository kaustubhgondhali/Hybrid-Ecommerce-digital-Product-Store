package com.hybrid.platform.catalog;

import jakarta.persistence.*;

import java.time.Instant;

/** A short video in the product gallery (MP4 or WebM), with an optional poster frame. */
@Entity
@Table(name = "product_videos")
public class ProductVideo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(name = "storage_key", nullable = false, updatable = false)
    private String storageKey;

    @Column(name = "poster_key", updatable = false)
    private String posterKey;

    private String title;

    @Column(name = "duration_seconds", updatable = false)
    private Integer durationSeconds;

    @Column(updatable = false)
    private Integer width;

    @Column(updatable = false)
    private Integer height;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProductVideo() {
    }

    public ProductVideo(String storageKey, String posterKey, String title, Integer durationSeconds, Integer width,
                        Integer height, long sizeBytes, int sortOrder) {
        this.storageKey = storageKey;
        this.posterKey = posterKey;
        this.title = title;
        this.durationSeconds = durationSeconds;
        this.width = width;
        this.height = height;
        this.sizeBytes = sizeBytes;
        this.sortOrder = sortOrder;
        this.createdAt = Instant.now();
    }

    void attach(Product product) {
        this.product = product;
    }

    public String url() {
        return "/media/" + storageKey;
    }

    public String posterUrl() {
        return posterKey == null ? null : "/media/" + posterKey;
    }

    /** MIME type from the stored extension (uploads are checked by their content, then saved as .mp4 or .webm). */
    public String contentType() {
        return storageKey.endsWith(".webm") ? "video/webm" : "video/mp4";
    }

    public Long getId() { return id; }
    public Product getProduct() { return product; }
    public String getStorageKey() { return storageKey; }
    public String getPosterKey() { return posterKey; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Integer getDurationSeconds() { return durationSeconds; }
    public Integer getWidth() { return width; }
    public Integer getHeight() { return height; }
    public long getSizeBytes() { return sizeBytes; }
    public int getSortOrder() { return sortOrder; }
    public Instant getCreatedAt() { return createdAt; }
}
