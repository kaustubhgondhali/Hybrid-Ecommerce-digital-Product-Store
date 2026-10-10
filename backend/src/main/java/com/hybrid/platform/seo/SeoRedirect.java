package com.hybrid.platform.seo;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "seo_redirects")
public class SeoRedirect {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Storefront storefront;

    @Column(name = "from_path", nullable = false)
    private String fromPath;

    @Column(name = "to_path", nullable = false)
    private String toPath;

    @Column(name = "status_code", nullable = false)
    private int statusCode = 301;

    @Column(name = "auto_created", nullable = false)
    private boolean autoCreated;

    @Column(nullable = false)
    private long hits;

    @Column(name = "last_hit_at")
    private Instant lastHitAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SeoRedirect() {
    }

    public SeoRedirect(Storefront storefront, String fromPath, String toPath, int statusCode, boolean autoCreated) {
        this.storefront = storefront;
        this.fromPath = fromPath;
        this.toPath = toPath;
        this.statusCode = statusCode;
        this.autoCreated = autoCreated;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Storefront getStorefront() { return storefront; }
    public String getFromPath() { return fromPath; }
    public void setFromPath(String fromPath) { this.fromPath = fromPath; }
    public String getToPath() { return toPath; }
    public void setToPath(String toPath) { this.toPath = toPath; }
    public int getStatusCode() { return statusCode; }
    public void setStatusCode(int statusCode) { this.statusCode = statusCode; }
    public boolean isAutoCreated() { return autoCreated; }
    public long getHits() { return hits; }
    public Instant getLastHitAt() { return lastHitAt; }
    public Instant getCreatedAt() { return createdAt; }
}
