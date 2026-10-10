package com.hybrid.platform.affiliate;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "affiliate_clicks")
public class AffiliateClick {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "click_ref", nullable = false)
    private String clickRef;

    @Column(name = "link_id", nullable = false)
    private Long linkId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Storefront storefront;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "ip_hash")
    private String ipHash;

    @Column(name = "ua_hash")
    private String uaHash;

    @Column(name = "referrer_host")
    private String referrerHost;

    @Column(name = "suspected_bot", nullable = false)
    private boolean suspectedBot;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AffiliateClick() {
    }

    public AffiliateClick(String clickRef, AffiliateLink link, Long customerId, String ipHash, String uaHash,
                          String referrerHost, boolean suspectedBot) {
        this.clickRef = clickRef;
        this.linkId = link.getId();
        this.storefront = link.getStorefront();
        this.productId = link.getProductId();
        this.customerId = customerId;
        this.ipHash = ipHash;
        this.uaHash = uaHash;
        this.referrerHost = referrerHost;
        this.suspectedBot = suspectedBot;
        this.createdAt = Instant.now();
    }

    public String getClickRef() { return clickRef; }
    public Long getLinkId() { return linkId; }
    public Storefront getStorefront() { return storefront; }
}
