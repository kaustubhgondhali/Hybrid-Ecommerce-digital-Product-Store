package com.hybrid.platform.delivery;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "digital_assets")
public class DigitalAsset {

    public enum Status { READY, RETIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Storefront storefront;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "storage_key", nullable = false, updatable = false)
    private String storageKey;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false)
    private String sha256;

    @Column(name = "version_label")
    private String versionLabel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "uploaded_by", nullable = false)
    private Long uploadedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DigitalAsset() {
    }

    public DigitalAsset(Storefront storefront, Long productId, String fileName, String storageKey, String contentType,
                        long sizeBytes, String sha256, String versionLabel, Long uploadedBy) {
        this.storefront = storefront;
        this.productId = productId;
        this.fileName = fileName;
        this.storageKey = storageKey;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.versionLabel = versionLabel;
        this.uploadedBy = uploadedBy;
        this.status = Status.READY;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Storefront getStorefront() { return storefront; }
    public Long getProductId() { return productId; }
    public String getFileName() { return fileName; }
    public String getStorageKey() { return storageKey; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public String getVersionLabel() { return versionLabel; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
}
