package com.hybrid.platform.crm;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

/** A follow-up reminder for staff ("call about the saved earbuds on Friday"). */
@Entity
@Table(name = "crm_tasks")
public class CrmTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Storefront storefront;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(nullable = false)
    private String title;

    @Column(name = "due_on")
    private LocalDate dueOn;

    @Column(name = "done_at")
    private Instant doneAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_by_email", nullable = false, updatable = false)
    private String createdByEmail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CrmTask() {
    }

    public CrmTask(Storefront storefront, Long customerId, String title, LocalDate dueOn, Long createdBy, String createdByEmail) {
        this.storefront = storefront;
        this.customerId = customerId;
        this.title = title;
        this.dueOn = dueOn;
        this.createdBy = createdBy;
        this.createdByEmail = createdByEmail;
        this.createdAt = Instant.now();
    }

    public void setDone(boolean done) {
        this.doneAt = done ? (doneAt == null ? Instant.now() : doneAt) : null;
    }

    public Long getId() { return id; }
    public Storefront getStorefront() { return storefront; }
    public Long getCustomerId() { return customerId; }
    public String getTitle() { return title; }
    public LocalDate getDueOn() { return dueOn; }
    public Instant getDoneAt() { return doneAt; }
    public String getCreatedByEmail() { return createdByEmail; }
    public Instant getCreatedAt() { return createdAt; }
}
