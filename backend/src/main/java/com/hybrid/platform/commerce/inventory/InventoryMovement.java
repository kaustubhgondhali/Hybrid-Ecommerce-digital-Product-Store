package com.hybrid.platform.commerce.inventory;

import jakarta.persistence.*;

import java.time.Instant;

/** Append-only stock ledger. */
@Entity
@Table(name = "inventory_movements")
public class InventoryMovement {

    public enum Reason { ADJUSTMENT, RECEIPT, SALE, RESTOCK, DAMAGE, CORRECTION }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "variant_id", nullable = false)
    private Long variantId;

    @Column(nullable = false)
    private int delta;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Reason reason;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "admin_id")
    private Long adminId;

    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected InventoryMovement() {
    }

    public InventoryMovement(Long variantId, int delta, Reason reason, Long orderId, Long adminId, String note) {
        this.variantId = variantId;
        this.delta = delta;
        this.reason = reason;
        this.orderId = orderId;
        this.adminId = adminId;
        this.note = note;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getVariantId() { return variantId; }
    public int getDelta() { return delta; }
    public Reason getReason() { return reason; }
    public Long getOrderId() { return orderId; }
    public String getNote() { return note; }
    public Instant getCreatedAt() { return createdAt; }
}
