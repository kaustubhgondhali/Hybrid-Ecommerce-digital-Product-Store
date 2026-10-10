package com.hybrid.platform.delivery;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "download_tokens")
public class DownloadToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "entitlement_id", nullable = false)
    private Long entitlementId;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DownloadToken() {
    }

    public DownloadToken(String tokenHash, Long entitlementId, Long assetId, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.entitlementId = entitlementId;
        this.assetId = assetId;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public Long getEntitlementId() { return entitlementId; }
    public Long getAssetId() { return assetId; }
    public Instant getExpiresAt() { return expiresAt; }
}
