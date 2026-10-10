package com.hybrid.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Per-IP limits on endpoints that can be abused for credential stuffing, spam or click fraud. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private record Route(String method, Pattern path, RateLimiter.Rule rule) {
    }

    private static final List<Route> ROUTES = List.of(
            post("^/api/auth/login$", "customer-login", 10, Duration.ofMinutes(5)),
            post("^/api/(shop|digital)/admin/auth/login$", "admin-login", 10, Duration.ofMinutes(5)),
            post("^/api/auth/register$", "register", 5, Duration.ofMinutes(30)),
            post("^/api/auth/password-reset/request$", "password-reset", 5, Duration.ofMinutes(30)),
            post("^/api/auth/verify-email/resend$", "verify-resend", 5, Duration.ofMinutes(30)),
            post("^/api/auth/(password-reset/confirm|verify-email)$", "token-confirm", 20, Duration.ofMinutes(15)),
            post("^/api/(shop|digital)/checkout/orders$", "place-order", 20, Duration.ofMinutes(10)),
            post("^/api/(shop|digital)/checkout/coupon$", "coupon-check", 30, Duration.ofMinutes(10)),
            post("^/api/(shop|digital)/checkout/quote$", "checkout-quote", 120, Duration.ofMinutes(10)),
            post("^/api/(shop|digital)/catalog/products/[^/]+/reviews$", "review", 10, Duration.ofHours(1)),
            post("^/api/(shop|digital)/admin/settings/smtp/test$", "smtp-test", 10, Duration.ofMinutes(10)),
            new Route("GET", Pattern.compile("^/api/(shop|digital)/catalog/pincode/.+$"),
                    new RateLimiter.Rule("pincode", 60, Duration.ofMinutes(1))),
            new Route("GET", Pattern.compile("^/go/.+$"), new RateLimiter.Rule("affiliate-redirect", 120, Duration.ofMinutes(1))),
            new Route("GET", Pattern.compile("^/api/(shop|digital)/affiliate/postback$"),
                    new RateLimiter.Rule("affiliate-postback", 600, Duration.ofMinutes(1))),
            post("^/api/(shop|digital)/affiliate/postback$", "affiliate-postback", 600, Duration.ofMinutes(1)));

    private static final Duration LONGEST_WINDOW = Duration.ofHours(1);

    private final RateLimiter limiter = new RateLimiter(Clock.systemUTC());
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public RateLimitFilter(ObjectMapper objectMapper, @Value("${app.rate-limit.enabled:true}") boolean enabled) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    private static Route post(String regex, String name, int limit, Duration window) {
        return new Route("POST", Pattern.compile(regex), new RateLimiter.Rule(name, limit, window));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        for (Route route : ROUTES) {
            if (route.method().equals(req.getMethod()) && route.path().matcher(path).matches()) {
                long retryAfter = limiter.tryAcquire(route.rule(), req.getRemoteAddr());
                if (retryAfter > 0) {
                    log.warn("Rate limit '{}' exceeded by {} on {}", route.rule().name(), req.getRemoteAddr(), path);
                    reject(res, path, retryAfter);
                    return;
                }
                break;
            }
        }
        chain.doFilter(req, res);
    }

    private void reject(HttpServletResponse res, String path, long retryAfter) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", 429);
        body.put("title", "Too Many Requests");
        body.put("detail", "Too many attempts. Please wait " + humanize(retryAfter) + " and try again.");
        body.put("code", "RATE_LIMITED");
        body.put("retryAfterSeconds", retryAfter);
        body.put("path", path);
        res.setStatus(429);
        res.setHeader("Retry-After", String.valueOf(retryAfter));
        res.setContentType("application/problem+json");
        objectMapper.writeValue(res.getOutputStream(), body);
    }

    private static String humanize(long seconds) {
        return seconds < 60 ? seconds + " seconds" : ((seconds + 59) / 60) + " minutes";
    }

    @Scheduled(fixedDelay = 300_000)
    void evict() {
        limiter.evictExpired(LONGEST_WINDOW);
    }
}
