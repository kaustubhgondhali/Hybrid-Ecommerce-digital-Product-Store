package com.hybrid.platform.crm;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;

/** A private staff note on a customer, visible only inside one storefront's admin. */
@Entity
@Table(name = "crm_notes")
public class CrmNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Storefront storefront;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "admin_id", nullable = false, updatable = false)
    private Long adminId;

    @Column(name = "admin_email", nullable = false, updatable = false)
    private String adminEmail;

    @Column(nullable = false)
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CrmNote() {
    }

    public CrmNote(Storefront storefront, Long customerId, Long adminId, String adminEmail, String body) {
        this.storefront = storefront;
        this.customerId = customerId;
        this.adminId = adminId;
        this.adminEmail = adminEmail;
        this.body = body;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Storefront getStorefront() { return storefront; }
    public Long getCustomerId() { return customerId; }
    public Long getAdminId() { return adminId; }
    public String getAdminEmail() { return adminEmail; }
    public String getBody() { return body; }
    public Instant getCreatedAt() { return createdAt; }
}
