package com.hybrid.platform.secaudit;

import com.hybrid.platform.affiliate.AffiliateMerchant;
import com.hybrid.platform.affiliate.AffiliateRepositories.MerchantRepository;
import com.hybrid.platform.common.AppProperties;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.identity.AdminRole;
import com.hybrid.platform.identity.AdminUser;
import com.hybrid.platform.identity.AdminUserRepository;
import com.hybrid.platform.ops.ErrorLogRepository;
import com.hybrid.platform.ops.SystemHealthService;
import com.hybrid.platform.payment.PaymentService;
import com.hybrid.platform.secaudit.SecurityAnalyzer.Analysis;
import com.hybrid.platform.secaudit.SecurityAnalyzer.Check;
import com.hybrid.platform.secaudit.SecurityAnalyzer.Snapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.domain.PageRequest;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the Security analyzer report for one storefront's admin panel. */
@Service
public class SecurityAdminService {

    public record NextStep(String id, String label, String fix, String status) {
    }

    public record Report(int score, String grade, long problems, long critical,
                         List<String> categories, Map<String, List<Check>> byCategory,
                         List<Check> checks, List<NextStep> nextSteps,
                         Map<String, Object> summary) {
    }

    private final AppProperties props;
    private final Environment env;
    private final ApplicationContext context;
    private final PaymentService payments;
    private final AdminUserRepository admins;
    private final MerchantRepository merchants;
    private final ErrorLogRepository errors;
    private final boolean rateLimitEnabled;
    private final boolean demoCatalog;
    private final boolean cookieSecure;
    private final boolean hstsEnabled;
    private final boolean permissionsPolicyEnabled;

    public SecurityAdminService(AppProperties props, Environment env, ApplicationContext context,
                                PaymentService payments, AdminUserRepository admins, MerchantRepository merchants,
                                ErrorLogRepository errors,
                                @Value("${app.rate-limit.enabled:true}") boolean rateLimitEnabled,
                                @Value("${app.demo-catalog:true}") boolean demoCatalog,
                                @Value("${server.servlet.session.cookie.secure:false}") boolean cookieSecure,
                                @Value("${app.security.hsts-enabled:false}") boolean hstsEnabled,
                                @Value("${app.security.permissions-policy-enabled:true}") boolean permissionsPolicyEnabled) {
        this.props = props;
        this.env = env;
        this.context = context;
        this.payments = payments;
        this.admins = admins;
        this.merchants = merchants;
        this.errors = errors;
        this.rateLimitEnabled = rateLimitEnabled;
        this.demoCatalog = demoCatalog;
        this.cookieSecure = cookieSecure;
        // HSTS is armed whenever secure cookies are on (see SecurityConfig).
        this.hstsEnabled = hstsEnabled || cookieSecure;
        this.permissionsPolicyEnabled = permissionsPolicyEnabled;
    }

    @Transactional(readOnly = true)
    public Report audit(Storefront sf) {
        List<AdminUser> staff = admins.findByStorefrontOrderByCreatedAtAsc(sf).stream()
                .filter(a -> a.getStatus() == AdminUser.Status.ACTIVE).toList();
        int withMfa = (int) staff.stream().filter(AdminUser::isMfaEnabled).count();
        List<AdminUser> masters = staff.stream().filter(a -> a.getRole() == AdminRole.MASTER_ADMIN).toList();
        int mastersMfa = (int) masters.stream().filter(AdminUser::isMfaEnabled).count();
        int mustChange = (int) staff.stream().filter(AdminUser::isMustChangePassword).count();
        int allowHttp = (int) merchants.findByStorefrontOrderByNameAsc(sf).stream()
                .filter(AffiliateMerchant::isActive).filter(AffiliateMerchant::isAllowHttp).count();
        long unresolved = errors.findByResolvedOrderByLastSeenDesc(false, PageRequest.of(0, 300)).stream()
                .filter(e -> SystemHealthService.visibleTo(sf, e.getPath())).count();

        String base = props.baseUrl() == null ? "" : props.baseUrl();
        var security = props.security();
        Snapshot snap = new Snapshot(
                base.startsWith("https://"),
                cookieSecure,
                props.encryptionKey() != null && !props.encryptionKey().isBlank(),
                props.hashSalt() != null && !props.hashSalt().isBlank(),
                env.acceptsProfiles(Profiles.of("prod")),
                rateLimitEnabled,
                security != null && security.requireEmailVerification(),
                payments.isDemo(),
                demoCatalog,
                context.getBeanNamesForType(SessionRepository.class).length > 0,
                security == null ? 5 : security.maxFailedLogins(),
                security == null ? 15 : security.lockMinutes(),
                staff.size(), withMfa, masters.size(), mastersMfa, mustChange, allowHttp, unresolved,
                true, true, true, hstsEnabled, permissionsPolicyEnabled, true);

        Analysis a = SecurityAnalyzer.analyze(snap);
        Map<String, List<Check>> byCategory = new LinkedHashMap<>();
        for (Check c : a.checks()) {
            byCategory.computeIfAbsent(c.category(), k -> new ArrayList<>()).add(c);
        }
        List<NextStep> next = a.checks().stream()
                .filter(c -> c.status() != SecurityAnalyzer.Status.PASS)
                .sorted((x, y) -> {
                    int sx = x.status() == SecurityAnalyzer.Status.FAIL ? 0 : 1;
                    int sy = y.status() == SecurityAnalyzer.Status.FAIL ? 0 : 1;
                    if (sx != sy) return Integer.compare(sx, sy);
                    return Integer.compare(y.weight(), x.weight());
                })
                .map(c -> new NextStep(c.id(), c.label(), c.fix(), c.status().name()))
                .toList();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("storefront", sf.name());
        summary.put("httpsBaseUrl", snap.httpsBaseUrl());
        summary.put("cookieSecure", snap.cookieSecure());
        summary.put("hstsEnabled", snap.hstsEnabled());
        summary.put("demoPayments", snap.demoPayments());
        summary.put("rateLimitEnabled", snap.rateLimitEnabled());
        summary.put("staffWithMfa", withMfa + " / " + staff.size());
        summary.put("emailVerificationRequired", snap.emailVerificationRequired());
        summary.put("prodProfile", snap.prodProfile());

        return new Report(a.score(), a.grade(), a.problems(), a.critical(),
                List.copyOf(byCategory.keySet()), byCategory, a.checks(), next, summary);
    }
}
