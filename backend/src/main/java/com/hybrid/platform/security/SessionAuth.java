package com.hybrid.platform.security;

import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.identity.Permission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Customer, shop-admin and digital-admin authentication live in separate session attributes, so being
 * signed in to one realm never grants anything in another, and signing out of one leaves the others intact.
 */
@Component
public class SessionAuth {

    public static final String CUSTOMER_KEY = "HPP_CTX_CUSTOMER";

    public static String adminKey(Storefront sf) {
        return "HPP_CTX_ADMIN_" + sf.name();
    }

    public void loginCustomer(HttpServletRequest request, CustomerPrincipal principal) {
        var auth = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        store(request, CUSTOMER_KEY, auth);
    }

    public void loginAdmin(HttpServletRequest request, AdminPrincipal principal) {
        store(request, adminKey(principal.storefront()), adminAuthentication(principal));
    }

    public static UsernamePasswordAuthenticationToken adminAuthentication(AdminPrincipal principal) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(principal.storefront().adminAuthority()));
        for (Permission p : principal.role().permissions()) {
            authorities.add(new SimpleGrantedAuthority(p.authority()));
        }
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
    }

    public void logoutCustomer(HttpServletRequest request) {
        clear(request, CUSTOMER_KEY);
    }

    public void logoutAdmin(HttpServletRequest request, Storefront sf) {
        clear(request, adminKey(sf));
    }

    private void store(HttpServletRequest request, String key, UsernamePasswordAuthenticationToken auth) {
        request.getSession(true);
        request.changeSessionId();
        SecurityContext ctx = new SecurityContextImpl(auth);
        request.getSession().setAttribute(key, ctx);
        SecurityContextHolder.setContext(ctx);
    }

    private void clear(HttpServletRequest request, String key) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(key);
        }
        SecurityContextHolder.clearContext();
    }
}
