package com.hybrid.platform.seo;

import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.audit.AuditService;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.security.AdminPrincipal;
import com.hybrid.platform.seo.SeoRepositories.SeoNotFoundRepository;
import com.hybrid.platform.seo.SeoRepositories.SeoRedirectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 301/302 redirects (manual and automatic when a URL slug changes) plus a 404 monitor.
 * Redirects are served from an in-memory copy; hit counters are buffered and flushed once a minute.
 */
@Service
public class SeoRedirectService {

    private static final Logger log = LoggerFactory.getLogger(SeoRedirectService.class);
    private static final Pattern SAFE_PATH = Pattern.compile("^/[A-Za-z0-9\\-._~%/]*$");
    private static final int MAX_404_ROWS = 5000;
    private static final int MAX_404_BUFFER = 2000;

    public record Target(Long id, String toPath, int status) {
    }

    public record RedirectView(Long id, String fromPath, String toPath, int statusCode, boolean autoCreated, long hits,
                               Instant lastHitAt, Instant createdAt) {
    }

    public record RedirectInput(String fromPath, String toPath, Integer statusCode) {
    }

    public record NotFoundView(Long id, String path, long hits, Instant firstSeen, Instant lastSeen) {
    }

    private record Pending404(Storefront sf, AtomicLong hits) {
    }

    private final SeoRedirectRepository redirects;
    private final SeoNotFoundRepository notFound;
    private final AuditService audit;
    private final TransactionTemplate tx;

    private volatile Map<String, Target> cache;
    private final Map<Long, AtomicLong> pendingHits = new ConcurrentHashMap<>();
    private final Map<String, Pending404> pending404 = new ConcurrentHashMap<>();

    public SeoRedirectService(SeoRedirectRepository redirects, SeoNotFoundRepository notFound, AuditService audit,
                              TransactionTemplate tx) {
        this.redirects = redirects;
        this.notFound = notFound;
        this.audit = audit;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ runtime

    public Target lookup(String path) {
        Map<String, Target> map = cache;
        if (map == null) {
            map = reload();
        }
        Target t = map.get(normalize(path));
        if (t != null) {
            pendingHits.computeIfAbsent(t.id(), k -> new AtomicLong()).incrementAndGet();
        }
        return t;
    }

    public void record404(Storefront sf, String path) {
        String p = normalize(path);
        if (p.length() > 500) return;
        Pending404 existing = pending404.get(p);
        if (existing != null) {
            existing.hits().incrementAndGet();
        } else if (pending404.size() < MAX_404_BUFFER) {
            pending404.computeIfAbsent(p, k -> new Pending404(sf, new AtomicLong())).hits().incrementAndGet();
        }
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void flush() {
        try {
            tx.executeWithoutResult(s -> {
                Instant now = Instant.now();
                for (var it = pendingHits.entrySet().iterator(); it.hasNext(); ) {
                    var e = it.next();
                    it.remove();
                    redirects.addHits(e.getKey(), e.getValue().get(), now);
                }
                for (var it = pending404.entrySet().iterator(); it.hasNext(); ) {
                    var e = it.next();
                    it.remove();
                    long hits = e.getValue().hits().get();
                    if (notFound.addHits(e.getKey(), hits, now) == 0
                            && notFound.countByStorefront(e.getValue().sf()) < MAX_404_ROWS) {
                        notFound.insert(e.getValue().sf().name(), e.getKey(), hits, now);
                    }
                }
            });
        } catch (RuntimeException e) {
            log.warn("Could not flush SEO counters: {}", e.getMessage());
        }
    }

    private synchronized Map<String, Target> reload() {
        Map<String, Target> map = new ConcurrentHashMap<>();
        for (SeoRedirect r : redirects.findAll()) {
            map.put(r.getFromPath(), new Target(r.getId(), r.getToPath(), r.getStatusCode()));
        }
        cache = map;
        return map;
    }

    private void invalidate() {
        cache = null;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    cache = null;
                }
            });
        }
    }

    // ------------------------------------------------------------------ automatic redirects

    /** Called when a public URL changes (e.g. product slug edited) so old links and search results keep working. */
    @Transactional
    public void autoRedirect(Storefront sf, String oldPath, String newPath) {
        String from = normalize(oldPath);
        String to = normalize(newPath);
        if (from.equals(to)) return;
        redirects.findByFromPath(to).ifPresent(redirects::delete);
        for (SeoRedirect chained : redirects.findByStorefrontAndToPath(sf, from)) {
            if (chained.getFromPath().equals(to)) redirects.delete(chained);
            else chained.setToPath(to);
        }
        SeoRedirect r = redirects.findByFromPath(from).orElse(null);
        if (r == null) {
            redirects.save(new SeoRedirect(sf, from, to, 301, true));
        } else {
            r.setToPath(to);
            r.setStatusCode(301);
        }
        notFound.deleteByStorefrontAndPath(sf, from);
        invalidate();
    }

    // ------------------------------------------------------------------ admin

    @Transactional(readOnly = true)
    public List<RedirectView> list(Storefront sf) {
        return redirects.findByStorefrontOrderByCreatedAtDesc(sf).stream().map(SeoRedirectService::view).toList();
    }

    @Transactional
    public RedirectView create(AdminPrincipal admin, RedirectInput in) {
        Storefront sf = admin.storefront();
        String from = validateFrom(sf, in.fromPath());
        String to = validateTo(in.toPath());
        if (from.equals(normalize(to))) throw ApiException.badRequest("REDIRECT_LOOP", "A page cannot redirect to itself");
        if (redirects.findByFromPath(from).isPresent()) {
            throw ApiException.conflict("REDIRECT_EXISTS", "A redirect from this path already exists");
        }
        SeoRedirect r = redirects.save(new SeoRedirect(sf, from, to, status(in.statusCode()), false));
        notFound.deleteByStorefrontAndPath(sf, from);
        audit.record(admin, "SEO_REDIRECT_CREATED", "SeoRedirect", r.getId(), from + " -> " + to);
        invalidate();
        return view(r);
    }

    @Transactional
    public RedirectView update(AdminPrincipal admin, Long id, RedirectInput in) {
        SeoRedirect r = redirects.findByIdAndStorefront(id, admin.storefront())
                .orElseThrow(() -> ApiException.notFound("REDIRECT_NOT_FOUND", "Redirect not found"));
        if (in.toPath() != null) r.setToPath(validateTo(in.toPath()));
        if (in.statusCode() != null) r.setStatusCode(status(in.statusCode()));
        if (r.getFromPath().equals(normalize(r.getToPath()))) {
            throw ApiException.badRequest("REDIRECT_LOOP", "A page cannot redirect to itself");
        }
        audit.record(admin, "SEO_REDIRECT_UPDATED", "SeoRedirect", id, r.getFromPath() + " -> " + r.getToPath());
        invalidate();
        return view(r);
    }

    @Transactional
    public void delete(AdminPrincipal admin, Long id) {
        SeoRedirect r = redirects.findByIdAndStorefront(id, admin.storefront())
                .orElseThrow(() -> ApiException.notFound("REDIRECT_NOT_FOUND", "Redirect not found"));
        redirects.delete(r);
        audit.record(admin, "SEO_REDIRECT_DELETED", "SeoRedirect", id, r.getFromPath());
        invalidate();
    }

    @Transactional(readOnly = true)
    public List<NotFoundView> notFound(Storefront sf) {
        return notFound.findByStorefrontOrderByHitsDesc(sf, PageRequest.of(0, 200)).stream()
                .map(n -> new NotFoundView(n.getId(), n.getPath(), n.getHits(), n.getFirstSeen(), n.getLastSeen())).toList();
    }

    @Transactional(readOnly = true)
    public long notFoundCount(Storefront sf) {
        return notFound.countByStorefront(sf);
    }

    @Transactional
    public void clearNotFound(AdminPrincipal admin, Long id) {
        if (id == null) {
            notFound.deleteByStorefront(admin.storefront());
        } else {
            notFound.findByIdAndStorefront(id, admin.storefront()).ifPresent(notFound::delete);
        }
    }

    // ------------------------------------------------------------------ helpers

    public static String normalize(String path) {
        if (path == null) return "/";
        String p = path.trim();
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        int h = p.indexOf('#');
        if (h >= 0) p = p.substring(0, h);
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p.isEmpty() ? "/" : p;
    }

    private static String validateFrom(Storefront sf, String raw) {
        String p = normalize(raw);
        String base = "/" + sf.path() + "/";
        if (!p.startsWith(base) || !SAFE_PATH.matcher(p).matches() || p.length() > 500) {
            throw ApiException.badRequest("INVALID_REDIRECT_FROM", "The old path must start with " + base + " and contain only URL-safe characters");
        }
        if (p.startsWith(base + "admin")) {
            throw ApiException.badRequest("INVALID_REDIRECT_FROM", "Admin URLs cannot be redirected");
        }
        return p;
    }

    private static String validateTo(String raw) {
        String t = raw == null ? "" : raw.trim();
        boolean local = t.startsWith("/") && !t.startsWith("//") && !t.contains("\\");
        boolean absolute = t.startsWith("https://") || t.startsWith("http://");
        if ((!local && !absolute) || t.length() > 500 || t.chars().anyMatch(c -> c < 0x21 || c == '"' || c == '<' || c == '>')) {
            throw ApiException.badRequest("INVALID_REDIRECT_TO", "The new address must be a path like /shop/p/new-name or a full http(s) URL");
        }
        return t;
    }

    private static int status(Integer code) {
        if (code == null) return 301;
        if (code != 301 && code != 302) throw ApiException.badRequest("INVALID_REDIRECT_STATUS", "Use 301 (permanent) or 302 (temporary)");
        return code;
    }

    private static RedirectView view(SeoRedirect r) {
        return new RedirectView(r.getId(), r.getFromPath(), r.getToPath(), r.getStatusCode(), r.isAutoCreated(), r.getHits(),
                r.getLastHitAt(), r.getCreatedAt());
    }
}
