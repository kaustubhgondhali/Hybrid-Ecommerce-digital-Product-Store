package com.hybrid.platform.commerce.order;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "invoices")
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Storefront storefront;

    @Column(name = "invoice_number", nullable = false, updatable = false)
    private String invoiceNumber;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    /** JSON snapshot of seller details at issue time; later settings changes never alter issued invoices. */
    @Column(name = "seller_snapshot", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String sellerSnapshot;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    protected Invoice() {
    }

    public Invoice(Storefront storefront, String invoiceNumber, Long orderId, String sellerSnapshot) {
        this.storefront = storefront;
        this.invoiceNumber = invoiceNumber;
        this.orderId = orderId;
        this.sellerSnapshot = sellerSnapshot;
        this.issuedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Storefront getStorefront() { return storefront; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public Long getOrderId() { return orderId; }
    public String getSellerSnapshot() { return sellerSnapshot; }
    public Instant getIssuedAt() { return issuedAt; }
}
