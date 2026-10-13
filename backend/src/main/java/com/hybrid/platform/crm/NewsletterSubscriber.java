package com.hybrid.platform.crm;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;

/** A visitor who asked for the deals email (no account needed); active once confirmed from their inbox. */
@Entity
@Table(name = "newsletter_subscribers")
public class NewsletterSubscriber {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Storefront storefront;

    @Column(nullable = false, updatable = false)
    private String email;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    private String source;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "unsubscribed_at")
    private Instant unsubscribedAt;

    /** ISO week of the last weekly deals email ("2026-W41"), so nobody gets it twice. */
    @Column(name = "last_digest_week")
    private String lastDigestWeek;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected NewsletterSubscriber() {
    }

    public NewsletterSubscriber(Storefront storefront, String email, String source) {
        this.storefront = storefront;
        this.email = email;
        this.source = source;
        this.createdAt = Instant.now();
    }

    public boolean isActive() {
        return confirmedAt != null && unsubscribedAt == null;
    }

    public Long getId() { return id; }
    public Storefront getStorefront() { return storefront; }
    public String getEmail() { return email; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public String getSource() { return source; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Instant confirmedAt) { this.confirmedAt = confirmedAt; }
    public Instant getUnsubscribedAt() { return unsubscribedAt; }
    public void setUnsubscribedAt(Instant unsubscribedAt) { this.unsubscribedAt = unsubscribedAt; }
    public String getLastDigestWeek() { return lastDigestWeek; }
    public void setLastDigestWeek(String lastDigestWeek) { this.lastDigestWeek = lastDigestWeek; }
    public Instant getCreatedAt() { return createdAt; }
}
