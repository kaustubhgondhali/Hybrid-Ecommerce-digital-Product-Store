package com.hybrid.platform.stories;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A Web Story: an automatic cover page (title + cover image) followed by the pages the team adds. */
@Entity
@Table(name = "web_stories")
public class WebStory {

    public enum Status { DRAFT, PUBLISHED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Storefront storefront;

    @Column(nullable = false)
    private String slug;

    @Column(nullable = false)
    private String title;

    private String description;

    @Column(name = "cover_key")
    private String coverKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "story", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    private List<WebStoryPage> pages = new ArrayList<>();

    protected WebStory() {
    }

    public WebStory(Storefront storefront, String slug, String title) {
        this.storefront = storefront;
        this.slug = slug;
        this.title = title;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /** Marks the story changed even when only its pages were edited (they live in another table). */
    public void changed() {
        updatedAt = Instant.now();
    }

    public WebStoryPage addPage(WebStoryPage page) {
        page.attach(this);
        page.setSortOrder(pages.isEmpty() ? 0 : pages.get(pages.size() - 1).getSortOrder() + 1);
        pages.add(page);
        changed();
        return page;
    }

    public String path() {
        return "/" + storefront.path() + "/stories/" + slug;
    }

    public String coverUrl() {
        return coverKey == null ? null : "/media/" + coverKey;
    }

    public boolean isPublished() {
        return status == Status.PUBLISHED;
    }

    public void publish() {
        if (status != Status.PUBLISHED) {
            status = Status.PUBLISHED;
            if (publishedAt == null) publishedAt = Instant.now();
        }
        changed();
    }

    public void unpublish() {
        status = Status.DRAFT;
        changed();
    }

    public Long getId() { return id; }
    public Storefront getStorefront() { return storefront; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCoverKey() { return coverKey; }
    public void setCoverKey(String coverKey) { this.coverKey = coverKey; }
    public Status getStatus() { return status; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<WebStoryPage> getPages() { return pages; }
}
