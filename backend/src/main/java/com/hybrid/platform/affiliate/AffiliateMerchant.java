package com.hybrid.platform.affiliate;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;

/** An admin-approved destination domain. Only approved domains are ever redirected to. */
@Entity
@Table(name = "affiliate_merchants")
public class AffiliateMerchant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Storefront storefront;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, updatable = false)
    private String domain;

    @Column(name = "sub_id_param")
    private String subIdParam;

    @Column(name = "allow_http", nullable = false)
    private boolean allowHttp;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AffiliateMerchant() {
    }

    public AffiliateMerchant(Storefront storefront, String name, String domain) {
        this.storefront = storefront;
        this.name = name;
        this.domain = domain;
        this.createdAt = Instant.now();
    }

    public boolean matchesHost(String host) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    public Long getId() { return id; }
    public Storefront getStorefront() { return storefront; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDomain() { return domain; }
    public String getSubIdParam() { return subIdParam; }
    public void setSubIdParam(String subIdParam) { this.subIdParam = subIdParam == null || subIdParam.isBlank() ? null : subIdParam.trim(); }
    public boolean isAllowHttp() { return allowHttp; }
    public void setAllowHttp(boolean allowHttp) { this.allowHttp = allowHttp; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
}
