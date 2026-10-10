package com.hybrid.platform.security;

import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.identity.AdminRole;

import java.io.Serializable;

public record AdminPrincipal(Long id, Storefront storefront, String email, String fullName, AdminRole role)
        implements Serializable {
}
