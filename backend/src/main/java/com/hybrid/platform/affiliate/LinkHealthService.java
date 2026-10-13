package com.hybrid.platform.affiliate;

import com.hybrid.platform.common.AppProperties;
import com.hybrid.platform.common.Storefront;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Checks that partner links still lead to a live page and hides offers whose page is gone, so shoppers never hit a
 * dead deal. Conservative on purpose: only a clear "not found" (404/410) on two checks in a row hides a link; stores
 * that block robots (403, 429, 503) or time out are left alone. Redirects are followed by hand and private / internal
 * addresses are never contacted.
 */
@Service
public class LinkHealthService {

    private static final Logger log = LoggerFactory.getLogger(LinkHealthService.class);
    static final int BROKEN_AFTER = 2;
    private static final int MAX_REDIRECTS = 6;

    public enum Verdict { OK, NOT_FOUND, UNKNOWN }

    public record Outcome(Verdict verdict, String detail) {
    }

    public record CheckResult(int checked, int ok, int broken, int unknown, List<String> newlyBroken, boolean safetyStop) {
    }

    private final AffiliateRepositories.LinkRepository links;
    private final TransactionTemplate tx;
    private final HttpClient http;
    private final String userAgent;
    private final boolean allowPrivate;

    public LinkHealthService(AffiliateRepositories.LinkRepository links, PlatformTransactionManager txManager, AppProperties props,
                             @Value("${app.affiliate.link-check-allow-private:false}") boolean allowPrivate) {
        this.links = links;
        this.tx = new TransactionTemplate(txManager);
        this.allowPrivate = allowPrivate;
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(8)).build();
        this.userAgent = "Mozilla/5.0 (compatible; ElaaLinkCheck/1.0; +" + (props.baseUrl() == null ? "" : props.baseUrl()) + ")";
    }

    private record Target(Long id, String url) {
    }

    /**
     * Checks up to {@code limit} active links of the storefront, waiting {@code pause} between requests. The web
     * requests run outside any database transaction; each result is saved in its own short transaction.
     */
    public CheckResult check(Storefront sf, int limit, Duration pause) {
        List<Target> targets = tx.execute(s -> links.findByStorefrontOrderByUpdatedAtDesc(sf).stream()
                .filter(l -> l.isActive() && l.getMerchant().isActive()).limit(limit)
                .map(l -> new Target(l.getId(), l.getDestinationUrl())).toList());
        List<Outcome> outcomes = new ArrayList<>();
        for (Target t : targets) {
            outcomes.add(probe(t.url()));
            sleep(pause);
        }
        long notFound = outcomes.stream().filter(o -> o.verdict() == Verdict.NOT_FOUND).count();
        // If many links "disappear" at once the problem is almost certainly on our side (network, blocking): hide nothing.
        boolean safetyStop = notFound > Math.max(3, targets.size() / 2);
        if (safetyStop) {
            log.warn("Link check for {}: {} of {} links returned 'not found'; not hiding any (check the server's network)",
                    sf, notFound, targets.size());
        }
        int[] counts = new int[3];
        List<String> newlyBroken = new ArrayList<>();
        Instant now = Instant.now();
        for (int i = 0; i < targets.size(); i++) {
            Long id = targets.get(i).id();
            Outcome o = outcomes.get(i);
            tx.executeWithoutResult(s -> links.findById(id).ifPresent(l -> {
                boolean wasBroken = l.isBroken();
                switch (o.verdict()) {
                    case OK -> l.recordHealth("OK", 0, o.detail(), now);
                    case NOT_FOUND -> {
                        int failures = safetyStop ? l.getHealthFailures() : l.getHealthFailures() + 1;
                        boolean broken = wasBroken || failures >= BROKEN_AFTER;
                        l.recordHealth(broken ? "BROKEN" : "UNKNOWN", failures,
                                o.detail() + (broken ? "" : " (" + failures + " of " + BROKEN_AFTER + " checks)"), now);
                    }
                    case UNKNOWN -> l.recordHealth(wasBroken ? "BROKEN" : "UNKNOWN", l.getHealthFailures(), o.detail(), now);
                }
                if (l.isBroken()) {
                    counts[1]++;
                    if (!wasBroken) newlyBroken.add(l.getMerchant().getName() + ": " + l.getDestinationUrl());
                } else if ("OK".equals(l.getHealthStatus())) {
                    counts[0]++;
                } else {
                    counts[2]++;
                }
            }));
        }
        if (!newlyBroken.isEmpty()) log.info("Link check for {} hid {} dead link(s)", sf, newlyBroken.size());
        return new CheckResult(targets.size(), counts[0], counts[1], counts[2], newlyBroken, safetyStop);
    }

    /** Fetches the URL (HEAD, falling back to GET) and follows redirects safely. */
    Outcome probe(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return new Outcome(Verdict.UNKNOWN, "Invalid URL");
        }
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
                return new Outcome(Verdict.UNKNOWN, "Unsupported address");
            }
            try {
                if (!allowPrivate && isPrivate(uri.getHost())) {
                    return new Outcome(Verdict.UNKNOWN, "Blocked: private network address");
                }
                HttpResponse<Void> res = send(uri, "HEAD");
                int code = res.statusCode();
                if (code == 405 || code == 501 || code == 403 || code == 400) {
                    res = send(uri, "GET");
                    code = res.statusCode();
                }
                if (code >= 300 && code < 400) {
                    String location = res.headers().firstValue("Location").orElse(null);
                    if (location == null) return new Outcome(Verdict.UNKNOWN, "Redirect without a location");
                    uri = uri.resolve(location.replace(" ", "%20"));
                    continue;
                }
                if (code >= 200 && code < 300) return new Outcome(Verdict.OK, "Page is live (HTTP " + code + ")");
                if (code == 404 || code == 410) return new Outcome(Verdict.NOT_FOUND, "Page not found (HTTP " + code + ")");
                return new Outcome(Verdict.UNKNOWN, "The store answered HTTP " + code + " (often robot blocking)");
            } catch (UnknownHostException e) {
                return new Outcome(Verdict.UNKNOWN, "Domain not found right now");
            } catch (IOException | IllegalArgumentException e) {
                return new Outcome(Verdict.UNKNOWN, "Could not connect: " + e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Outcome(Verdict.UNKNOWN, "Interrupted");
            }
        }
        return new Outcome(Verdict.UNKNOWN, "Too many redirects");
    }

    private HttpResponse<Void> send(URI uri, String method) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12))
                .header("User-Agent", userAgent).header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        return http.send(req, HttpResponse.BodyHandlers.discarding());
    }

    static boolean isPrivate(String host) throws UnknownHostException {
        for (InetAddress a : InetAddress.getAllByName(host)) {
            if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                    || a.isMulticastAddress() || (a instanceof Inet6Address && (a.getAddress()[0] & 0xfe) == 0xfc)) {
                return true;
            }
        }
        return false;
    }

    private static void sleep(Duration d) {
        if (d.isZero() || d.isNegative()) return;
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
