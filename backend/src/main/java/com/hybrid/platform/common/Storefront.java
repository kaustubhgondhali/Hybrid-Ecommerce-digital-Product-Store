package com.hybrid.platform.common;

import com.hybrid.platform.common.error.ApiException;

import java.util.Locale;

public enum Storefront {
    SHOP("shop"),
    DIGITAL("digital");

    private final String path;

    Storefront(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }

    public String adminAuthority() {
        return "ROLE_ADMIN_" + name();
    }

    public static Storefront fromPath(String value) {
        if (value != null) {
            String v = value.toLowerCase(Locale.ROOT);
            for (Storefront sf : values()) {
                if (sf.path.equals(v)) {
                    return sf;
                }
            }
        }
        throw ApiException.notFound("STOREFRONT_NOT_FOUND", "Unknown storefront");
    }
}
