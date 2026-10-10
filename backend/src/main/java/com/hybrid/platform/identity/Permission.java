package com.hybrid.platform.identity;

public enum Permission {
    DASHBOARD_READ,
    CATALOG_READ,
    CATALOG_WRITE,
    INVENTORY_WRITE,
    ORDERS_READ,
    ORDERS_WRITE,
    REFUNDS,
    DIGITAL_WRITE,
    AFFILIATE_READ,
    AFFILIATE_WRITE,
    SETTINGS_WRITE,
    STAFF_MANAGE,
    AUDIT_READ;

    public String authority() {
        return "PERM_" + name();
    }
}
