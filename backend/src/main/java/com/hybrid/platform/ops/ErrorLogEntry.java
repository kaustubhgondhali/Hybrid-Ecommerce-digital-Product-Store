package com.hybrid.platform.ops;

import jakarta.persistence.*;

import java.time.Instant;

/** One distinct unexpected server error (grouped by fingerprint) with how often and when it happened. */
@Entity
@Table(name = "error_log")
public class ErrorLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 64)
    private String fingerprint;

    private String method;

    private String path;

    @Column(name = "error_type", nullable = false)
    private String errorType;

    private String message;

    @Column(columnDefinition = "TEXT")
    private String stack;

    @Column(nullable = false)
    private long occurrences;

    @Column(name = "first_seen", nullable = false)
    private Instant firstSeen;

    @Column(name = "last_seen", nullable = false)
    private Instant lastSeen;

    @Column(nullable = false)
    private boolean resolved;

    protected ErrorLogEntry() {
    }

    public ErrorLogEntry(String fingerprint, String method, String path, String errorType, String message, String stack) {
        this.fingerprint = fingerprint;
        this.method = method;
        this.path = path;
        this.errorType = errorType;
        this.message = message;
        this.stack = stack;
        this.occurrences = 1;
        this.firstSeen = Instant.now();
        this.lastSeen = firstSeen;
    }

    public Long getId() { return id; }
    public String getFingerprint() { return fingerprint; }
    public String getMethod() { return method; }
    public String getPath() { return path; }
    public String getErrorType() { return errorType; }
    public String getMessage() { return message; }
    public String getStack() { return stack; }
    public long getOccurrences() { return occurrences; }
    public Instant getFirstSeen() { return firstSeen; }
    public Instant getLastSeen() { return lastSeen; }
    public boolean isResolved() { return resolved; }
    public void setResolved(boolean resolved) { this.resolved = resolved; }
}
