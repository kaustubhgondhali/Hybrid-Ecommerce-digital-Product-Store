package com.hybrid.platform.ops;

import com.hybrid.platform.common.crypto.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Records unexpected server errors so admins can see them on the System health page without reading log files.
 * Identical errors are grouped by type, first application stack frame and URL pattern.
 */
@Service
public class ErrorLogService {

    private static final Logger log = LoggerFactory.getLogger(ErrorLogService.class);
    private static final Pattern ID_SEGMENT = Pattern.compile("/(\\d+|[A-Za-z0-9_-]{20,})(?=/|$)");
    private static final int STACK_FRAMES = 25;

    private final ErrorLogRepository errors;
    private final TransactionTemplate tx;

    public ErrorLogService(ErrorLogRepository errors, PlatformTransactionManager txManager) {
        this.errors = errors;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Never throws; a failure to record must not hide the original error. */
    public void record(String method, String requestPath, Throwable error) {
        try {
            String path = normalisePath(requestPath);
            String type = error.getClass().getName();
            String message = truncate(error.getMessage(), 1000);
            String fingerprint = Hashing.sha256Hex(type + "|" + firstAppFrame(error) + "|" + method + " " + path);
            Instant now = Instant.now();
            try {
                upsert(fingerprint, now, method, path, type, message, error);
            } catch (DataIntegrityViolationException race) {
                tx.executeWithoutResult(s -> errors.bump(fingerprint, now, message));
            }
        } catch (RuntimeException e) {
            log.warn("Could not record error in error_log: {}", e.getMessage());
        }
    }

    private void upsert(String fingerprint, Instant now, String method, String path, String type, String message, Throwable error) {
        tx.executeWithoutResult(s -> {
            if (errors.bump(fingerprint, now, message) == 0) {
                errors.saveAndFlush(new ErrorLogEntry(fingerprint, truncate(method, 10), truncate(path, 500),
                        truncate(type, 200), message, stack(error)));
            }
        });
    }

    @Scheduled(cron = "0 40 3 * * *")
    public void purgeOldResolved() {
        tx.executeWithoutResult(s -> errors.deleteResolvedBefore(Instant.now().minus(Duration.ofDays(30))));
    }

    /** Replaces numeric ids and long tokens with {id} so the same endpoint groups together and tokens are not stored. */
    static String normalisePath(String path) {
        if (path == null) return null;
        String p = path;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        String previous;
        do {
            previous = p;
            p = ID_SEGMENT.matcher(p).replaceAll("/{id}");
        } while (!p.equals(previous));
        return p;
    }

    private static String firstAppFrame(Throwable error) {
        for (StackTraceElement f : error.getStackTrace()) {
            if (f.getClassName().startsWith("com.hybrid.platform")) {
                return f.getClassName() + "." + f.getMethodName() + ":" + f.getLineNumber();
            }
        }
        StackTraceElement[] st = error.getStackTrace();
        return st.length == 0 ? "" : st[0].toString();
    }

    private static String stack(Throwable error) {
        StringBuilder sb = new StringBuilder();
        Throwable t = error;
        int depth = 0;
        while (t != null && depth < 4) {
            if (depth > 0) sb.append("Caused by: ");
            sb.append(t.getClass().getName()).append(": ").append(truncate(t.getMessage(), 300)).append('\n');
            StackTraceElement[] frames = t.getStackTrace();
            for (int i = 0; i < Math.min(frames.length, STACK_FRAMES); i++) {
                sb.append("    at ").append(frames[i]).append('\n');
            }
            t = t.getCause() == t ? null : t.getCause();
            depth++;
        }
        return truncate(sb.toString(), 20000);
    }

    private static String truncate(String s, int max) {
        return s == null ? null : (s.length() > max ? s.substring(0, max) : s);
    }
}
