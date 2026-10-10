package com.hybrid.platform.identity;

import com.hybrid.platform.common.Storefront;

public final class IdentityEvents {

    private IdentityEvents() {
    }

    public record CustomerRegistered(Long customerId, String email, String fullName, String verifyToken,
                                     Storefront storefront) {
    }

    public record PasswordResetRequested(String email, String fullName, String resetToken, Storefront storefront) {
    }
}
