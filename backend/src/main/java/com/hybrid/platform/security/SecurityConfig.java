package com.hybrid.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.identity.AdminUserRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Four filter chains:
 * 1. /api/shop/admin/**    – shop admin realm only
 * 2. /api/digital/admin/** – digital admin realm only
 * 3. /api/**, /go/**       – customers and public API
 * 4. everything else       – static pages (public; data access is protected by the API chains)
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    // blob: lets the admin preview a chosen video and its captured poster frame before uploading.
    private static final String CSP = "default-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; style-src 'self'; script-src 'self'; "
            + "font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; "
            + "frame-ancestors 'none'; upgrade-insecure-requests";

    /**
     * Storefront pages may also load Google Analytics 4 and the Meta Pixel. Their scripts are only added after the
     * shopper accepts analytics cookies and only when an ID is set in Settings; admin and API responses keep {@link #CSP}.
     */
    private static final String STORE_CSP = CSP
            .replace("img-src 'self' data: blob:", "img-src 'self' data: blob: https://*.google-analytics.com https://*.googletagmanager.com https://www.facebook.com")
            .replace("script-src 'self'", "script-src 'self' https://*.googletagmanager.com https://connect.facebook.net")
            .replace("connect-src 'self'", "connect-src 'self' https://*.google-analytics.com https://*.analytics.google.com "
                    + "https://*.googletagmanager.com https://www.facebook.com https://connect.facebook.net");

    /** Deny powerful browser features this store never needs. */
    private static final String PERMISSIONS_POLICY =
            "accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), "
                    + "payment=(), publickey-credentials-get=(), usb=(), interest-cohort=()";

    private final ObjectMapper objectMapper;
    private final AdminUserRepository adminUsers;
    private final boolean cookieSecure;

    public SecurityConfig(ObjectMapper objectMapper, AdminUserRepository adminUsers,
                          @Value("${server.servlet.session.cookie.secure:false}") boolean cookieSecure) {
        this.objectMapper = objectMapper;
        this.adminUsers = adminUsers;
        this.cookieSecure = cookieSecure;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain shopAdminChain(HttpSecurity http) throws Exception {
        return adminChain(http, Storefront.SHOP);
    }

    @Bean
    @Order(2)
    public SecurityFilterChain digitalAdminChain(HttpSecurity http) throws Exception {
        return adminChain(http, Storefront.DIGITAL);
    }

    private SecurityFilterChain adminChain(HttpSecurity http, Storefront sf) throws Exception {
        String base = "/api/" + sf.path() + "/admin";
        common(http, SessionAuth.adminKey(sf));
        http.securityMatcher(base + "/**")
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, base + "/auth/login").permitAll()
                        .anyRequest().hasAuthority(sf.adminAuthority()))
                .addFilterBefore(new AdminSessionFilter(adminUsers, sf, objectMapper), AuthorizationFilter.class);
        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        common(http, SessionAuth.CUSTOMER_KEY);
        http.securityMatcher("/api/**", "/go/**")
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/go/**").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/shop/catalog/**", "/api/digital/catalog/**").permitAll()
                        .requestMatchers("/api/payments/webhooks/**").permitAll()
                        .requestMatchers("/api/*/affiliate/postback").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/marketing/unsubscribe").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/shop/price-alerts", "/api/digital/price-alerts",
                                "/api/shop/newsletter", "/api/digital/newsletter",
                                "/api/shop/catalog/products/*/view", "/api/digital/catalog/products/*/view").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/price-alerts/**", "/api/newsletter/**").permitAll()
                        .anyRequest().hasRole("CUSTOMER"));
        return http.build();
    }

    @Bean
    @Order(4)
    public SecurityFilterChain webChain(HttpSecurity http) throws Exception {
        common(http, SessionAuth.CUSTOMER_KEY, STORE_CSP);
        http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
        return http.build();
    }

    private void common(HttpSecurity http, String contextKey) throws Exception {
        common(http, contextKey, CSP);
    }

    private void common(HttpSecurity http, String contextKey, String policy) throws Exception {
        HttpSessionSecurityContextRepository repo = new HttpSessionSecurityContextRepository();
        repo.setSpringSecurityContextKey(contextKey);
        CookieCsrfTokenRepository csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepo.setCookieCustomizer(c -> c.sameSite("Lax"));

        http.securityContext(c -> c.securityContextRepository(repo))
                .csrf(c -> c.csrfTokenRepository(csrfRepo)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers("/api/payments/webhooks/**", "/api/*/affiliate/postback"))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .sessionManagement(s -> s.sessionFixation().none())
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(e -> e.authenticationEntryPoint(jsonEntryPoint()).accessDeniedHandler(jsonDenied()))
                .headers(h -> {
                    h.contentSecurityPolicy(csp -> csp.policyDirectives(cookieSecure
                                    ? policy
                                    : policy.replace("; upgrade-insecure-requests", "")))
                            .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                            .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                            .contentTypeOptions(Customizer.withDefaults())
                            .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", PERMISSIONS_POLICY));
                    // HSTS only when cookies are Secure — otherwise local HTTP development breaks for a year.
                    if (cookieSecure) {
                        h.httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000));
                    } else {
                        h.httpStrictTransportSecurity(HeadersConfigurer.HstsConfig::disable);
                    }
                });
    }

    private AuthenticationEntryPoint jsonEntryPoint() {
        return (req, res, ex) -> writeProblem(res, 401, "UNAUTHENTICATED", "Please sign in", req.getRequestURI());
    }

    private AccessDeniedHandler jsonDenied() {
        return (req, res, ex) -> {
            boolean csrf = ex.getClass().getSimpleName().contains("Csrf");
            writeProblem(res, 403, csrf ? "CSRF_INVALID" : "FORBIDDEN",
                    csrf ? "Security token expired. Reload the page and try again." : "You do not have permission",
                    req.getRequestURI());
        };
    }

    void writeProblem(HttpServletResponse res, int status, String code, String detail, String path) throws IOException {
        writeProblem(objectMapper, res, status, code, detail, path);
    }

    static void writeProblem(ObjectMapper mapper, HttpServletResponse res, int status, String code, String detail,
                             String path) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("title", status == 401 ? "Unauthorized" : "Forbidden");
        body.put("detail", detail);
        body.put("code", code);
        body.put("path", path);
        res.setStatus(status);
        res.setContentType("application/problem+json");
        mapper.writeValue(res.getOutputStream(), body);
    }
}
