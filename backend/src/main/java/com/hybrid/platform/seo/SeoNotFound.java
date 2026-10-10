package com.hybrid.platform.seo;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "seo_not_found")
public class SeoNotFound {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Storefront storefront;

    @Column(nullable = false, updatable = false)
    private String path;

    @Column(nullable = false)
    private long hits;

    @Column(name = "first_seen", nullable = false, updatable = false)
    private Instant firstSeen;

    @Column(name = "last_seen", nullable = false)
    private Instant lastSeen;

    protected SeoNotFound() {
    }

    public Long getId() { return id; }
    public Storefront getStorefront() { return storefront; }
    public String getPath() { return path; }
    public long getHits() { return hits; }
    public Instant getFirstSeen() { return firstSeen; }
    public Instant getLastSeen() { return lastSeen; }
}
