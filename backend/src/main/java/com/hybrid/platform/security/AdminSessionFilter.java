package com.hybrid.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.identity.AdminUser;
import com.hybrid.platform.identity.AdminUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/**
 * Re-checks the admin account on every admin request so that disabling an admin or changing their role
 * takes effect immediately, and enforces the forced password change after first login / reset.
 */
final class AdminSessionFilter extends OncePerRequestFilter {

    private static final Set<String> ALLOWED_WHILE_PASSWORD_CHANGE = Set.of("/auth/me", "/auth/change-password", "/auth/logout");

    private final AdminUserRepository adminUsers;
    private final Storefront storefront;
    private final ObjectMapper mapper;

    AdminSessionFilter(AdminUserRepository adminUsers, Storefront storefront, ObjectMapper mapper) {
        this.adminUsers = adminUsers;
        this.storefront = storefront;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AdminPrincipal principal) {
            Optional<AdminUser> current = adminUsers.findById(principal.id());
            boolean valid = current.isPresent()
                    && current.get().getStorefront() == storefront
                    && principal.storefront() == storefront
                    && current.get().getStatus() == AdminUser.Status.ACTIVE
                    && current.get().getRole() == principal.role();
            if (!valid) {
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.removeAttribute(SessionAuth.adminKey(storefront));
                }
                SecurityContextHolder.clearContext();
                SecurityConfig.writeProblem(mapper, response, 401, "SESSION_REVOKED",
                        "Your admin session is no longer valid. Please sign in again.", request.getRequestURI());
                return;
            }
            if (current.get().isMustChangePassword()) {
                String suffix = request.getRequestURI().substring(("/api/" + storefront.path() + "/admin").length());
                if (!ALLOWED_WHILE_PASSWORD_CHANGE.contains(suffix)) {
                    SecurityConfig.writeProblem(mapper, response, 403, "PASSWORD_CHANGE_REQUIRED",
                            "You must change your password before continuing.", request.getRequestURI());
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }
}
