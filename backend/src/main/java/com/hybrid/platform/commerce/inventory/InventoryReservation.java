package com.hybrid.platform.commerce.inventory;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "inventory_reservations")
public class InventoryReservation {

    public enum Status { ACTIVE, COMMITTED, RELEASED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "variant_id", nullable = false)
    private Long variantId;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected InventoryReservation() {
    }

    public InventoryReservation(Long orderId, Long variantId, int quantity) {
        this.orderId = orderId;
        this.variantId = variantId;
        this.quantity = quantity;
        this.status = Status.ACTIVE;
        this.createdAt = Instant.now();
    }

    public Long getOrderId() { return orderId; }
    public Long getVariantId() { return variantId; }
    public int getQuantity() { return quantity; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
}
