package com.hybrid.platform.crm;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/** A customer's choice about marketing email from one storefront. Absent row = never opted in. */
@Entity
@Table(name = "marketing_consents")
@IdClass(MarketingConsent.Key.class)
public class MarketingConsent {

    public enum Source { SIGNUP, ACCOUNT, UNSUBSCRIBE_LINK }

    public static class Key implements Serializable {
        private Long customerId;
        private Storefront storefront;

        public Key() {
        }

        public Key(Long customerId, Storefront storefront) {
            this.customerId = customerId;
            this.storefront = storefront;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(customerId, k.customerId) && storefront == k.storefront;
        }

        @Override
        public int hashCode() {
            return Objects.hash(customerId, storefront);
        }
    }

    @Id
    @Column(name = "customer_id")
    private Long customerId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "storefront")
    private Storefront storefront;

    @Column(nullable = false)
    private boolean subscribed;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MarketingConsent() {
    }

    public MarketingConsent(Long customerId, Storefront storefront, boolean subscribed, Source source) {
        this.customerId = customerId;
        this.storefront = storefront;
        set(subscribed, source);
    }

    public void set(boolean subscribed, Source source) {
        this.subscribed = subscribed;
        this.source = source;
        this.updatedAt = Instant.now();
    }

    public Long getCustomerId() { return customerId; }
    public Storefront getStorefront() { return storefront; }
    public boolean isSubscribed() { return subscribed; }
    public Source getSource() { return source; }
    public Instant getUpdatedAt() { return updatedAt; }
}
