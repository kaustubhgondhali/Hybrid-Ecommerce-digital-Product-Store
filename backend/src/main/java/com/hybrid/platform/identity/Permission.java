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
    AUDIT_READ,
    /** See CRM customer profiles, segments and campaign results. */
    CRM_READ,
    /** Add notes, tags and follow-up tasks to customers. */
    CRM_WRITE,
    /** Send email campaigns and manage automated reminders. */
    MARKETING_SEND;

    public String authority() {
        return "PERM_" + name();
    }
}
