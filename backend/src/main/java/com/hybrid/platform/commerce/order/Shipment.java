package com.hybrid.platform.commerce.order;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "shipments")
public class Shipment {

    public enum Status { SHIPPED, DELIVERED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(nullable = false)
    private String carrier;

    @Column(name = "tracking_number")
    private String trackingNumber;

    @Column(name = "tracking_url")
    private String trackingUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Shipment() {
    }

    public Shipment(Long orderId, String carrier, String trackingNumber, String trackingUrl) {
        this.orderId = orderId;
        this.carrier = carrier;
        this.trackingNumber = trackingNumber;
        this.trackingUrl = trackingUrl;
        this.status = Status.SHIPPED;
        this.createdAt = Instant.now();
        this.shippedAt = createdAt;
    }

    public void markDelivered() {
        status = Status.DELIVERED;
        deliveredAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public String getCarrier() { return carrier; }
    public String getTrackingNumber() { return trackingNumber; }
    public String getTrackingUrl() { return trackingUrl; }
    public Status getStatus() { return status; }
    public Instant getShippedAt() { return shippedAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
}
