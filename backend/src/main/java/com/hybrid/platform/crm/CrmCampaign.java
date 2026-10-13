package com.hybrid.platform.crm;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;

/** A marketing email to one segment. Goes DRAFT -> SENDING -> SENT; only subscribed customers receive it. */
@Entity
@Table(name = "crm_campaigns")
public class CrmCampaign {

    public enum Status { DRAFT, SENDING, SENT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Storefront storefront;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CrmSegments.Segment segment;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String body;

    @Column(name = "coupon_code")
    private String couponCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private int recipients;

    @Column(name = "created_by_email", nullable = false, updatable = false)
    private String createdByEmail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected CrmCampaign() {
    }

    public CrmCampaign(Storefront storefront, String createdByEmail) {
        this.storefront = storefront;
        this.createdByEmail = createdByEmail;
        this.status = Status.DRAFT;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public void edit(String name, CrmSegments.Segment segment, String subject, String body, String couponCode) {
        this.name = name;
        this.segment = segment;
        this.subject = subject;
        this.body = body;
        this.couponCode = couponCode;
    }

    public void markSent(int recipients) {
        this.status = Status.SENT;
        this.recipients = recipients;
        this.sentAt = Instant.now();
    }

    public Long getId() { return id; }
    public Storefront getStorefront() { return storefront; }
    public String getName() { return name; }
    public CrmSegments.Segment getSegment() { return segment; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public String getCouponCode() { return couponCode; }
    public Status getStatus() { return status; }
    public int getRecipients() { return recipients; }
    public String getCreatedByEmail() { return createdByEmail; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getSentAt() { return sentAt; }
}
