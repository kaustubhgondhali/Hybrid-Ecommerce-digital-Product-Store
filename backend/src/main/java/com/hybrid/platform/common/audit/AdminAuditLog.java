package com.hybrid.platform.common.audit;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "admin_audit_log")
public class AdminAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Storefront storefront;

    @Column(name = "admin_id", nullable = false)
    private Long adminId;

    @Column(name = "admin_email", nullable = false)
    private String adminEmail;

    @Column(nullable = false)
    private String action;

    @Column(name = "entity_type")
    private String entityType;

    @Column(name = "entity_id")
    private String entityId;

    private String details;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AdminAuditLog() {
    }

    public AdminAuditLog(Storefront storefront, Long adminId, String adminEmail, String action,
                         String entityType, String entityId, String details) {
        this.storefront = storefront;
        this.adminId = adminId;
        this.adminEmail = adminEmail;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.details = details == null ? null : details.substring(0, Math.min(details.length(), 1000));
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getAdminEmail() { return adminEmail; }
    public String getAction() { return action; }
    public String getEntityType() { return entityType; }
    public String getEntityId() { return entityId; }
    public String getDetails() { return details; }
    public Instant getCreatedAt() { return createdAt; }
}
