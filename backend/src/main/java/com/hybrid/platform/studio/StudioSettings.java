package com.hybrid.platform.studio;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;

/** Per-storefront switches for content generated automatically when a product is published. All off by default. */
@Entity
@Table(name = "content_studio_settings")
public class StudioSettings {

    /** What to do with automatically generated blog posts and stories. */
    public enum Mode { OFF, DRAFT, PUBLISH }

    @Id
    @Enumerated(EnumType.STRING)
    private Storefront storefront;

    @Enumerated(EnumType.STRING)
    @Column(name = "auto_blog", nullable = false)
    private Mode autoBlog = Mode.OFF;

    @Enumerated(EnumType.STRING)
    @Column(name = "blog_kind", nullable = false)
    private ContentWriter.BlogKind blogKind = ContentWriter.BlogKind.REVIEW;

    @Enumerated(EnumType.STRING)
    @Column(name = "auto_story", nullable = false)
    private Mode autoStory = Mode.OFF;

    @Column(name = "auto_newsletter", nullable = false)
    private boolean autoNewsletter;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StudioSettings() {
    }

    public StudioSettings(Storefront storefront) {
        this.storefront = storefront;
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public Storefront getStorefront() { return storefront; }
    public Mode getAutoBlog() { return autoBlog; }
    public void setAutoBlog(Mode v) { this.autoBlog = v; }
    public ContentWriter.BlogKind getBlogKind() { return blogKind; }
    public void setBlogKind(ContentWriter.BlogKind v) { this.blogKind = v; }
    public Mode getAutoStory() { return autoStory; }
    public void setAutoStory(Mode v) { this.autoStory = v; }
    public boolean isAutoNewsletter() { return autoNewsletter; }
    public void setAutoNewsletter(boolean v) { this.autoNewsletter = v; }
    public Instant getUpdatedAt() { return updatedAt; }
}
