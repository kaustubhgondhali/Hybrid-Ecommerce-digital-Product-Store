package com.hybrid.platform.ops;

import com.hybrid.platform.catalog.ProductSearch;
import com.hybrid.platform.common.AppProperties;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.audit.AuditService;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.notification.NotificationLog;
import com.hybrid.platform.notification.NotificationRepositories.NotificationLogRepository;
import com.hybrid.platform.payment.PaymentService;
import com.hybrid.platform.security.AdminPrincipal;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.List;

/** Read-only operational overview for the admin System health page. */
@Service
public class SystemHealthService {

    public record ErrorView(Long id, String method, String path, String errorType, String message, String stack,
                            long occurrences, Instant firstSeen, Instant lastSeen, boolean resolved) {
    }

    public record Health(boolean databaseUp, String database, String schemaVersion, Instant startedAt, long uptimeSeconds,
                         String javaVersion, long heapUsedMb, long heapMaxMb, boolean demoPayments, String searchMode,
                         String sessionStore, long emailsPending, long emailsFailed, long emailsSkipped,
                         Long storageFreeMb, Long storageTotalMb, boolean httpsBaseUrl, long unresolvedErrors,
                         List<ErrorView> errors) {
    }

    private final JdbcTemplate jdbc;
    private final ErrorLogRepository errors;
    private final NotificationLogRepository notifications;
    private final PaymentService payments;
    private final ProductSearch search;
    private final ApplicationContext context;
    private final AppProperties props;
    private final AuditService audit;

    public SystemHealthService(JdbcTemplate jdbc, ErrorLogRepository errors, NotificationLogRepository notifications,
                               PaymentService payments, ProductSearch search, ApplicationContext context,
                               AppProperties props, AuditService audit) {
        this.jdbc = jdbc;
        this.errors = errors;
        this.notifications = notifications;
        this.payments = payments;
        this.search = search;
        this.context = context;
        this.props = props;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Health health(Storefront sf, boolean showResolved) {
        boolean dbUp;
        String database = null;
        String schema = null;
        try {
            database = jdbc.execute((java.sql.Connection c) -> c.getMetaData().getDatabaseProductName() + " " + c.getMetaData().getDatabaseProductVersion());
            dbUp = true;
            schema = jdbc.queryForObject(
                    "select version from flyway_schema_history where success = true and version is not null order by installed_rank desc limit 1",
                    String.class);
        } catch (RuntimeException e) {
            dbUp = database != null;
        }
        var runtime = ManagementFactory.getRuntimeMXBean();
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        File storage = new File(props.storage() == null || props.storage().root() == null ? "." : props.storage().root());
        boolean storageOk = storage.exists();
        boolean jdbcSessions = context.getBeanNamesForType(SessionRepository.class).length > 0;
        String base = props.baseUrl() == null ? "" : props.baseUrl();
        List<ErrorView> list = errors.findByResolvedOrderByLastSeenDesc(showResolved, PageRequest.of(0, 300)).stream()
                .filter(e -> visibleTo(sf, e.getPath()))
                .limit(50)
                .map(e -> new ErrorView(e.getId(), e.getMethod(), e.getPath(), e.getErrorType(), e.getMessage(), e.getStack(),
                        e.getOccurrences(), e.getFirstSeen(), e.getLastSeen(), e.isResolved()))
                .toList();
        long unresolved = showResolved
                ? errors.findByResolvedOrderByLastSeenDesc(false, PageRequest.of(0, 300)).stream().filter(e -> visibleTo(sf, e.getPath())).count()
                : list.size();
        return new Health(dbUp, database, schema, Instant.ofEpochMilli(runtime.getStartTime()), runtime.getUptime() / 1000,
                System.getProperty("java.version"), heap.getUsed() / (1024 * 1024), heap.getMax() / (1024 * 1024),
                payments.isDemo(), search.available() ? "MySQL full-text index" : "Simple text matching (LIKE)",
                jdbcSessions ? "Database (survives restarts)" : "Server memory (lost on restart)",
                notifications.countByStorefrontAndStatus(sf, NotificationLog.Status.PENDING),
                notifications.countByStorefrontAndStatus(sf, NotificationLog.Status.FAILED),
                notifications.countByStorefrontAndStatus(sf, NotificationLog.Status.SKIPPED),
                storageOk ? storage.getUsableSpace() / (1024 * 1024) : null,
                storageOk ? storage.getTotalSpace() / (1024 * 1024) : null,
                base.startsWith("https://"), unresolved, list);
    }

    /** Errors on the other storefront's URLs belong to that storefront's admins. */
    public static boolean visibleTo(Storefront sf, String path) {
        if (path == null) return true;
        for (Storefront other : Storefront.values()) {
            if (other == sf) continue;
            String p = other.path();
            if (path.startsWith("/api/" + p + "/") || path.equals("/" + p) || path.startsWith("/" + p + "/")) return false;
        }
        return true;
    }

    @Transactional
    public void setResolved(AdminPrincipal admin, Long id, boolean resolved) {
        ErrorLogEntry e = errors.findById(id).filter(x -> visibleTo(admin.storefront(), x.getPath()))
                .orElseThrow(() -> ApiException.notFound("ERROR_NOT_FOUND", "Error not found"));
        e.setResolved(resolved);
        audit.record(admin, resolved ? "ERROR_RESOLVED" : "ERROR_REOPENED", "ErrorLog", id, e.getErrorType());
    }
}
