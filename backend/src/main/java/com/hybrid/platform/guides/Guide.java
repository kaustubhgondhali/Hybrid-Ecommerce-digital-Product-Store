package com.hybrid.platform.guides;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A buying guide or blog article. The body uses the content-page text format plus [product: slug] cards. */
@Entity
@Table(name = "guides")
public class Guide {

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

    private String summary;

    @Column(nullable = false)
    private String body;

    @Column(name = "author_name")
    private String authorName;

    @Column(name = "meta_title")
    private String metaTitle;

    @Column(name = "meta_description")
    private String metaDescription;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ElementCollection
    @CollectionTable(name = "guide_products", joinColumns = @JoinColumn(name = "guide_id"))
    @OrderColumn(name = "position")
    @Column(name = "product_id")
    private List<Long> productIds = new ArrayList<>();

    protected Guide() {
    }

    public Guide(Storefront storefront) {
        this.storefront = storefront;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public void publish() {
        if (status != Status.PUBLISHED) {
            status = Status.PUBLISHED;
            if (publishedAt == null) publishedAt = Instant.now();
        }
    }

    public void unpublish() {
        status = Status.DRAFT;
    }

    public boolean isPublished() { return status == Status.PUBLISHED; }

    public String path() {
        return "/" + storefront.path() + "/guides/" + slug;
    }

    public Long getId() { return id; }
    public Storefront getStorefront() { return storefront; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public String getAuthorName() { return authorName; }
    public void setAuthorName(String authorName) { this.authorName = authorName; }
    public String getMetaTitle() { return metaTitle; }
    public void setMetaTitle(String metaTitle) { this.metaTitle = metaTitle; }
    public String getMetaDescription() { return metaDescription; }
    public void setMetaDescription(String metaDescription) { this.metaDescription = metaDescription; }
    public Status getStatus() { return status; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<Long> getProductIds() { return productIds; }

    public void setProductIds(List<Long> ids) {
        productIds.clear();
        productIds.addAll(ids);
    }
}
