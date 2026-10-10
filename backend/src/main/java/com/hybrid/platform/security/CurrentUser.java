package com.hybrid.platform.security;

import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.error.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

public final class CurrentUser {

    private CurrentUser() {
    }

    public static Optional<CustomerPrincipal> optionalCustomer() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CustomerPrincipal p) {
            return Optional.of(p);
        }
        return Optional.empty();
    }

    public static CustomerPrincipal customer() {
        return optionalCustomer().orElseThrow(
                () -> ApiException.unauthorized("UNAUTHENTICATED", "Please sign in"));
    }

    /** Returns the admin for this request, enforcing that the admin belongs to the storefront in the URL. */
    public static AdminPrincipal admin(Storefront storefront) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AdminPrincipal p) {
            if (p.storefront() != storefront) {
                throw ApiException.forbidden("WRONG_STOREFRONT", "This account cannot manage this storefront");
            }
            return p;
        }
        throw ApiException.unauthorized("UNAUTHENTICATED", "Please sign in");
    }
}
