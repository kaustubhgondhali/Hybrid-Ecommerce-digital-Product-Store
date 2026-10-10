package com.hybrid.platform.seo;

import com.hybrid.platform.common.Storefront;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Applies admin-defined redirects to storefront pages and records storefront 404s for the SEO tool. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class SeoRedirectFilter extends OncePerRequestFilter {

    private final SeoRedirectService service;

    public SeoRedirectFilter(SeoRedirectService service) {
        this.service = service;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String m = request.getMethod();
        if (!"GET".equals(m) && !"HEAD".equals(m)) return true;
        return storefront(request.getRequestURI()) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        SeoRedirectService.Target target = service.lookup(uri);
        if (target != null) {
            String location = target.toPath();
            String query = request.getQueryString();
            if (query != null && location.startsWith("/") && !location.contains("?")) location += "?" + query;
            response.setStatus(target.status());
            response.setHeader("Location", location);
            response.setHeader("Cache-Control", target.status() == 301 ? "public, max-age=3600" : "no-store");
            return;
        }
        chain.doFilter(request, response);
        if (response.getStatus() == HttpServletResponse.SC_NOT_FOUND) {
            service.record404(storefront(uri), uri);
        }
    }

    private static Storefront storefront(String uri) {
        for (Storefront sf : Storefront.values()) {
            String base = "/" + sf.path() + "/";
            if (uri.startsWith(base) && !uri.startsWith(base + "admin")) return sf;
        }
        return null;
    }
}
