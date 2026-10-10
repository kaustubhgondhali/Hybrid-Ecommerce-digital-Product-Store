package com.hybrid.platform.notification;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * One outgoing email. Doubles as the outbox: PENDING rows keep the (encrypted) body until they are delivered or
 * retries are exhausted.
 */
@Entity
@Table(name = "notification_log")
public class NotificationLog {

    public enum Status { PENDING, SENT, FAILED, SKIPPED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Storefront storefront;

    @Column(nullable = false)
    private String recipient;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private String error;

    /** Encrypted message body; cleared once the email is sent or skipped. */
    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected NotificationLog() {
    }

    public NotificationLog(Storefront storefront, String recipient, String subject, String kind, Status status, String error) {
        this.storefront = storefront;
        this.recipient = recipient;
        this.subject = subject.length() > 250 ? subject.substring(0, 250) : subject;
        this.kind = kind;
        this.status = status;
        this.error = trim(error);
        this.createdAt = Instant.now();
        this.sentAt = status == Status.SENT ? createdAt : null;
    }

    public static NotificationLog pending(Storefront storefront, String recipient, String subject, String kind, String encryptedBody) {
        NotificationLog l = new NotificationLog(storefront, recipient, subject, kind, Status.PENDING, null);
        l.body = encryptedBody;
        l.nextAttemptAt = l.createdAt;
        return l;
    }

    public void markSent() {
        status = Status.SENT;
        sentAt = Instant.now();
        attempts++;
        error = null;
        body = null;
        nextAttemptAt = null;
    }

    public void markSkipped(String reason) {
        status = Status.SKIPPED;
        error = trim(reason);
        body = null;
        nextAttemptAt = null;
    }

    /** Records a failed attempt; schedules a retry or gives up when {@code retryAt} is null. */
    public void markAttemptFailed(String reason, Instant retryAt) {
        attempts++;
        error = trim(reason);
        if (retryAt == null) {
            status = Status.FAILED;
            nextAttemptAt = null;
        } else {
            status = Status.PENDING;
            nextAttemptAt = retryAt;
        }
    }

    public void requeue() {
        status = Status.PENDING;
        attempts = 0;
        nextAttemptAt = Instant.now();
    }

    private static String trim(String s) {
        return s == null ? null : (s.length() > 500 ? s.substring(0, 500) : s);
    }

    public Long getId() { return id; }
    public Storefront getStorefront() { return storefront; }
    public String getRecipient() { return recipient; }
    public String getSubject() { return subject; }
    public String getKind() { return kind; }
    public Status getStatus() { return status; }
    public String getError() { return error; }
    public String getBody() { return body; }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }
}
