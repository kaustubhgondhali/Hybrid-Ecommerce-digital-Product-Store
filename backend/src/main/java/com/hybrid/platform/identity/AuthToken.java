package com.hybrid.platform.identity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "auth_tokens")
public class AuthToken {

    public enum Purpose { EMAIL_VERIFY, PASSWORD_RESET }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Purpose purpose;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuthToken() {
    }

    public AuthToken(String tokenHash, Purpose purpose, Long customerId, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.purpose = purpose;
        this.customerId = customerId;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public boolean isUsable(Purpose expected, Instant now) {
        return purpose == expected && usedAt == null && expiresAt.isAfter(now);
    }

    public void markUsed() {
        usedAt = Instant.now();
    }

    public Long getCustomerId() { return customerId; }
}
