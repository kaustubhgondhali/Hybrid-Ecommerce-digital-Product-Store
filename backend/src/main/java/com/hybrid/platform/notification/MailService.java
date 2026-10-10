package com.hybrid.platform.notification;

import com.hybrid.platform.common.AppProperties;
import com.hybrid.platform.common.PageResponse;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.audit.AuditService;
import com.hybrid.platform.common.crypto.CryptoService;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.notification.NotificationRepositories.NotificationLogRepository;
import com.hybrid.platform.notification.NotificationRepositories.SmtpSettingsRepository;
import com.hybrid.platform.security.AdminPrincipal;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Sends email through the SMTP server configured by each storefront's admins. Credentials live encrypted in
 * the database, so they can be changed from the admin panel without a redeploy.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public record SmtpView(String storefront, boolean enabled, String host, Integer port, String username,
                           boolean passwordSet, String security, String fromEmail, String fromName, Instant updatedAt) {
    }

    /** password: null keeps the stored password, an empty string clears it. */
    public record SmtpInput(Boolean enabled, String host, Integer port, String username, String password,
                            SmtpSettings.Security security, String fromEmail, String fromName) {
    }

    public record LogEntry(Long id, String recipient, String subject, String kind, String status, String error,
                           Instant createdAt, Instant sentAt, int attempts, Instant nextAttemptAt, boolean retryable) {
    }

    private final SmtpSettingsRepository settings;
    private final NotificationLogRepository logs;
    private final CryptoService crypto;
    private final AuditService audit;
    private final AppProperties props;

    public MailService(SmtpSettingsRepository settings, NotificationLogRepository logs, CryptoService crypto,
                       AuditService audit, AppProperties props) {
        this.settings = settings;
        this.logs = logs;
        this.crypto = crypto;
        this.audit = audit;
        this.props = props;
    }

    // ------------------------------------------------------------------ settings

    @Transactional
    public SmtpView get(Storefront sf) {
        return view(entity(sf));
    }

    @Transactional
    public SmtpView update(AdminPrincipal admin, SmtpInput in) {
        SmtpSettings s = entity(admin.storefront());
        if (in.host() != null) {
            String host = in.host().trim();
            if (!host.isEmpty() && (host.length() > 253 || !HOST.matcher(host).matches())) {
                throw ApiException.badRequest("SMTP_HOST_INVALID", "SMTP host is not a valid host name");
            }
            s.setHost(host.isEmpty() ? null : host);
        }
        if (in.port() != null) {
            if (in.port() < 1 || in.port() > 65535) {
                throw ApiException.badRequest("SMTP_PORT_INVALID", "Port must be between 1 and 65535");
            }
            s.setPort(in.port());
        }
        if (in.username() != null) {
            String u = in.username().trim();
            if (u.length() > 254) throw ApiException.badRequest("SMTP_USERNAME_INVALID", "Username is too long");
            s.setUsername(u.isEmpty() ? null : u);
        }
        if (in.password() != null) {
            if (in.password().length() > 500) throw ApiException.badRequest("SMTP_PASSWORD_INVALID", "Password is too long");
            s.setPasswordEnc(in.password().isEmpty() ? null : crypto.encrypt(in.password()));
        }
        if (in.security() != null) s.setSecurity(in.security());
        if (in.fromEmail() != null) {
            String f = in.fromEmail().trim();
            if (!f.isEmpty() && (f.length() > 254 || !EMAIL.matcher(f).matches())) {
                throw ApiException.badRequest("SMTP_FROM_INVALID", "From email is not valid");
            }
            s.setFromEmail(f.isEmpty() ? null : f);
        }
        if (in.fromName() != null) {
            String n = in.fromName().trim().replaceAll("[\\r\\n]", "");
            if (n.length() > 120) throw ApiException.badRequest("SMTP_FROM_NAME_INVALID", "From name is too long");
            s.setFromName(n.isEmpty() ? null : n);
        }
        if (in.enabled() != null) s.setEnabled(in.enabled());
        if (s.isEnabled() && !s.isComplete()) {
            throw ApiException.rule("SMTP_INCOMPLETE", "Host, port and from email are required before enabling email");
        }
        s.setUpdatedBy(admin.id());
        settings.save(s);
        audit.record(admin, "SMTP_SETTINGS_UPDATED", "SmtpSettings", admin.storefront().name(),
                "enabled=" + s.isEnabled() + " host=" + s.getHost() + (in.password() != null ? " passwordChanged" : ""));
        return view(s);
    }

    /** Sends synchronously so the admin sees the SMTP server's error. Not transactional: the log row must survive a failure. */
    public void sendTest(AdminPrincipal admin, String to) {
        if (to == null || !EMAIL.matcher(to.trim()).matches()) {
            throw ApiException.badRequest("INVALID_EMAIL", "Enter a valid recipient address");
        }
        SmtpSettings s = entity(admin.storefront());
        if (!s.isComplete()) {
            throw ApiException.rule("SMTP_INCOMPLETE", "Save the host, port and from email first");
        }
        String subject = "Test email from your " + admin.storefront().path() + " store";
        try {
            deliver(s, to.trim(), subject, "This is a test message. Your SMTP settings are working.");
            logs.save(new NotificationLog(admin.storefront(), to.trim(), subject, "SMTP_TEST", NotificationLog.Status.SENT, null));
        } catch (Exception e) {
            logs.save(new NotificationLog(admin.storefront(), to.trim(), subject, "SMTP_TEST", NotificationLog.Status.FAILED, rootMessage(e)));
            throw ApiException.rule("SMTP_TEST_FAILED", "Sending failed: " + rootMessage(e));
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<LogEntry> log(Storefront sf, int page, int size) {
        return PageResponse.of(logs.findByStorefrontOrderByCreatedAtDesc(sf, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100))),
                l -> new LogEntry(l.getId(), l.getRecipient(), l.getSubject(), l.getKind(), l.getStatus().name(), l.getError(),
                        l.getCreatedAt(), l.getSentAt(), l.getAttempts(), l.getNextAttemptAt(),
                        l.getStatus() == NotificationLog.Status.FAILED && l.getBody() != null));
    }

    // ------------------------------------------------------------------ sending

    /**
     * Queues an email in the outbox and tries to deliver it straight away. Failed deliveries are retried by
     * {@link #dispatchDue()} with backoff. Never throws.
     */
    public void send(Storefront sf, String to, String subject, String body, String kind) {
        NotificationLog row;
        try {
            SmtpSettings s = settings.findById(sf).orElse(null);
            if (s == null || !s.isEnabled() || !s.isComplete()) {
                logs.save(new NotificationLog(sf, to, subject, kind, NotificationLog.Status.SKIPPED, "SMTP is not configured"));
                if (props.mail().logUnsentEmails()) {
                    log.info("[email not sent: SMTP disabled for {}] to={} subject={}\n{}", sf, to, subject, body);
                }
                return;
            }
            row = logs.save(NotificationLog.pending(sf, to, subject, kind, crypto.encrypt(body)));
        } catch (RuntimeException e) {
            log.warn("Could not queue email '{}' to {}: {}", kind, to, rootMessage(e));
            return;
        }
        Instant now = Instant.now();
        if (logs.claim(row.getId(), now, now.plus(LEASE)) == 1) {
            attempt(row.getId());
        }
    }

    static final java.time.Duration LEASE = java.time.Duration.ofMinutes(5);
    private static final java.time.Duration[] BACKOFF = {
            java.time.Duration.ofMinutes(1), java.time.Duration.ofMinutes(5), java.time.Duration.ofMinutes(15),
            java.time.Duration.ofHours(1), java.time.Duration.ofHours(3)};

    /** Delivers queued emails whose retry time has come. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${app.mail.outbox-interval-ms:60000}", initialDelay = 30_000)
    public void dispatchDue() {
        Instant now = Instant.now();
        for (Long id : logs.dueIds(now, PageRequest.of(0, 25))) {
            if (logs.claim(id, now, now.plus(LEASE)) == 1) {
                attempt(id);
            }
        }
    }

    /** Failed emails can be retried for a week; after that their bodies (which may hold one-time links) are erased. */
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 15 3 * * *")
    public void purgeOldBodies() {
        int n = logs.purgeFailedBodies(Instant.now().minus(java.time.Duration.ofDays(7)));
        if (n > 0) log.info("Erased the bodies of {} failed emails older than 7 days", n);
    }

    private void attempt(Long id) {
        NotificationLog row = logs.findById(id).orElse(null);
        if (row == null || row.getStatus() != NotificationLog.Status.PENDING || row.getBody() == null) return;
        SmtpSettings s = settings.findById(row.getStorefront()).orElse(null);
        if (s == null || !s.isEnabled() || !s.isComplete()) {
            row.markSkipped("SMTP was turned off before the email could be sent");
            logs.save(row);
            return;
        }
        try {
            deliver(s, row.getRecipient(), row.getSubject(), crypto.decrypt(row.getBody()));
            row.markSent();
        } catch (Exception e) {
            int done = row.getAttempts() + 1;
            Instant retryAt = done <= BACKOFF.length ? Instant.now().plus(BACKOFF[done - 1]) : null;
            log.warn("Email '{}' to {} failed (attempt {}): {}", row.getKind(), row.getRecipient(), done, rootMessage(e));
            row.markAttemptFailed(rootMessage(e), retryAt);
        }
        logs.save(row);
    }

    /** Puts a failed email back in the queue (admin action). */
    @Transactional
    public void retry(AdminPrincipal admin, Long id) {
        NotificationLog row = logs.findById(id).filter(l -> l.getStorefront() == admin.storefront())
                .orElseThrow(() -> ApiException.notFound("NOTIFICATION_NOT_FOUND", "Email not found"));
        if (row.getStatus() != NotificationLog.Status.FAILED || row.getBody() == null) {
            throw ApiException.conflict("NOT_RETRYABLE", "Only failed emails can be retried");
        }
        row.requeue();
        audit.record(admin, "EMAIL_RETRIED", "NotificationLog", id, row.getKind());
    }

    private void deliver(SmtpSettings s, String to, String subject, String body) throws Exception {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(s.getHost());
        sender.setPort(s.getPort());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        Properties p = sender.getJavaMailProperties();
        p.put("mail.transport.protocol", "smtp");
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "15000");
        p.put("mail.smtp.writetimeout", "15000");
        if (s.getUsername() != null) {
            sender.setUsername(s.getUsername());
            sender.setPassword(s.getPasswordEnc() == null ? "" : crypto.decrypt(s.getPasswordEnc()));
            p.put("mail.smtp.auth", "true");
        }
        switch (s.getSecurity()) {
            case STARTTLS -> {
                p.put("mail.smtp.starttls.enable", "true");
                p.put("mail.smtp.starttls.required", "true");
            }
            case SSL -> p.put("mail.smtp.ssl.enable", "true");
            case NONE -> { }
        }
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
        helper.setFrom(s.getFromName() == null ? new InternetAddress(s.getFromEmail())
                : new InternetAddress(s.getFromEmail(), s.getFromName(), StandardCharsets.UTF_8.name()));
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(body, false);
        sender.send(message);
    }

    private SmtpSettings entity(Storefront sf) {
        return settings.findById(sf).orElseGet(() -> settings.save(new SmtpSettings(sf)));
    }

    private static SmtpView view(SmtpSettings s) {
        return new SmtpView(s.getStorefront().path(), s.isEnabled(), s.getHost(), s.getPort(), s.getUsername(),
                s.getPasswordEnc() != null, s.getSecurity().name(), s.getFromEmail(), s.getFromName(), s.getUpdatedAt());
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return msg == null ? t.getClass().getSimpleName() : msg;
    }
}
