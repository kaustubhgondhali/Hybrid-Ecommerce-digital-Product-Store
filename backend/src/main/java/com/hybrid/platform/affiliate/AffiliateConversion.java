package com.hybrid.platform.affiliate;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "affiliate_conversions")
public class AffiliateConversion {

    public enum Status { PENDING, APPROVED, REVERSED, PAID }

    public enum Source { IMPORT, MANUAL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Storefront storefront;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "click_ref")
    private String clickRef;

    @Column(name = "external_txn_id", nullable = false)
    private String externalTxnId;

    @Column(name = "sale_amount_minor")
    private Long saleAmountMinor;

    @Column(name = "commission_minor")
    private Long commissionMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source;

    private String note;

    @Column(name = "reported_at", nullable = false)
    private Instant reportedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AffiliateConversion() {
    }

    public AffiliateConversion(Storefront storefront, String externalTxnId, Source source) {
        this.storefront = storefront;
        this.externalTxnId = externalTxnId;
        this.source = source;
        this.status = Status.PENDING;
        this.reportedAt = Instant.now();
        this.updatedAt = reportedAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Storefront getStorefront() { return storefront; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public String getClickRef() { return clickRef; }
    public void setClickRef(String clickRef) { this.clickRef = clickRef; }
    public String getExternalTxnId() { return externalTxnId; }
    public Long getSaleAmountMinor() { return saleAmountMinor; }
    public void setSaleAmountMinor(Long saleAmountMinor) { this.saleAmountMinor = saleAmountMinor; }
    public Long getCommissionMinor() { return commissionMinor; }
    public void setCommissionMinor(Long commissionMinor) { this.commissionMinor = commissionMinor; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Source getSource() { return source; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Instant getReportedAt() { return reportedAt; }
}
