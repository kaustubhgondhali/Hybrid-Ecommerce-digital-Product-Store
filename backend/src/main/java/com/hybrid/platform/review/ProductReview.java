package com.hybrid.platform.review;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "product_reviews")
public class ProductReview {

    public enum Status { PENDING, APPROVED, REJECTED }

    /** Where the review came from. Only WEBSITE reviews are written by a signed-in customer. */
    public enum Source {
        WEBSITE(null), EMAIL("Shared with us by email"), WHATSAPP("Shared with us on WhatsApp"), PHONE("Shared with us by phone"),
        IN_PERSON("Shared with us in person"), OTHER("Shared with us directly");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        /** Shown next to the review on the storefront; null for reviews written on the website. */
        public String label() {
            return label;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Storefront storefront;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    /** Null for reviews the store team added on a customer's behalf. */
    @Column(name = "customer_id", updatable = false)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source = Source.WEBSITE;

    /** Admin who added the review (only for reviews not written on the website). */
    @Column(name = "added_by", updatable = false)
    private String addedBy;

    @Column(name = "author_name", nullable = false)
    private String authorName;

    @Column(nullable = false)
    private int rating;

    private String title;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "verified_purchase", nullable = false)
    private boolean verifiedPurchase;

    @Column(name = "admin_reply", columnDefinition = "TEXT")
    private String adminReply;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProductReview() {
    }

    public ProductReview(Storefront storefront, Long productId, Long customerId) {
        this.storefront = storefront;
        this.productId = productId;
        this.customerId = customerId;
        this.status = Status.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Storefront getStorefront() { return storefront; }
    public Long getProductId() { return productId; }
    public Long getCustomerId() { return customerId; }
    public String getAuthorName() { return authorName; }
    public void setAuthorName(String authorName) { this.authorName = authorName; }
    public int getRating() { return rating; }
    public void setRating(int rating) { this.rating = rating; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public boolean isVerifiedPurchase() { return verifiedPurchase; }
    public void setVerifiedPurchase(boolean verifiedPurchase) { this.verifiedPurchase = verifiedPurchase; }
    public String getAdminReply() { return adminReply; }
    public void setAdminReply(String adminReply) { this.adminReply = adminReply; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Source getSource() { return source; }
    public String getAddedBy() { return addedBy; }

    /** Marks a review the store team entered for feedback received elsewhere. */
    public void markAddedByStaff(Source source, String adminEmail) {
        this.source = source;
        this.addedBy = adminEmail;
    }
}
