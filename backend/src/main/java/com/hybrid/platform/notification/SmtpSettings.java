package com.hybrid.platform.notification;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "smtp_settings")
public class SmtpSettings {

    public enum Security { STARTTLS, SSL, NONE }

    @Id
    @Enumerated(EnumType.STRING)
    private Storefront storefront;

    @Column(nullable = false)
    private boolean enabled;

    private String host;

    private Integer port;

    private String username;

    /** AES-GCM ciphertext; the plaintext password is never returned by any API. */
    @Column(name = "password_enc")
    private String passwordEnc;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Security security = Security.STARTTLS;

    @Column(name = "from_email")
    private String fromEmail;

    @Column(name = "from_name")
    private String fromName;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected SmtpSettings() {
    }

    public SmtpSettings(Storefront storefront) {
        this.storefront = storefront;
        this.updatedAt = Instant.now();
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public boolean isComplete() {
        return host != null && port != null && fromEmail != null;
    }

    public Storefront getStorefront() { return storefront; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordEnc() { return passwordEnc; }
    public void setPasswordEnc(String passwordEnc) { this.passwordEnc = passwordEnc; }
    public Security getSecurity() { return security; }
    public void setSecurity(Security security) { this.security = security; }
    public String getFromEmail() { return fromEmail; }
    public void setFromEmail(String fromEmail) { this.fromEmail = fromEmail; }
    public String getFromName() { return fromName; }
    public void setFromName(String fromName) { this.fromName = fromName; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
}
