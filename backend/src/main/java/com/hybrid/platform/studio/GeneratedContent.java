package com.hybrid.platform.studio;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;

/** One piece of content the studio created (a guide, story or newsletter draft), for the history list. */
@Entity
@Table(name = "content_generation_log")
public class GeneratedContent {

    public enum Kind { BLOG, STORY, NEWSLETTER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Storefront storefront;

    @Column(name = "product_id")
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Column(name = "target_id")
    private Long targetId;

    private String title;

    @Column(nullable = false)
    private boolean automatic;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected GeneratedContent() {
    }

    public GeneratedContent(Storefront storefront, Long productId, Kind kind, Long targetId, String title, boolean automatic) {
        this.storefront = storefront;
        this.productId = productId;
        this.kind = kind;
        this.targetId = targetId;
        this.title = title == null || title.length() <= 200 ? title : title.substring(0, 200);
        this.automatic = automatic;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Storefront getStorefront() { return storefront; }
    public Long getProductId() { return productId; }
    public Kind getKind() { return kind; }
    public Long getTargetId() { return targetId; }
    public String getTitle() { return title; }
    public boolean isAutomatic() { return automatic; }
    public Instant getCreatedAt() { return createdAt; }
}
