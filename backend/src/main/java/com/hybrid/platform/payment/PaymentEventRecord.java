package com.hybrid.platform.payment;

import jakarta.persistence.*;

import java.time.Instant;

/** Provider callbacks already processed; the unique (provider, provider_event_id) makes processing idempotent. */
@Entity
@Table(name = "payment_events")
public class PaymentEventRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String provider;

    @Column(name = "provider_event_id", nullable = false)
    private String providerEventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected PaymentEventRecord() {
    }

    public PaymentEventRecord(String provider, String providerEventId, String eventType, Long paymentId) {
        this.provider = provider;
        this.providerEventId = providerEventId;
        this.eventType = eventType;
        this.paymentId = paymentId;
        this.receivedAt = Instant.now();
    }
}
