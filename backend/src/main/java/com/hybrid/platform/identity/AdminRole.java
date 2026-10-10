package com.hybrid.platform.identity;

import java.util.EnumSet;
import java.util.Set;

import static com.hybrid.platform.identity.Permission.*;

/** Roles exist only inside one storefront. There is intentionally no cross-storefront role. */
public enum AdminRole {
    MASTER_ADMIN(EnumSet.allOf(Permission.class)),
    CATALOG_MANAGER(EnumSet.of(DASHBOARD_READ, CATALOG_READ, CATALOG_WRITE, DIGITAL_WRITE, AFFILIATE_READ, AFFILIATE_WRITE)),
    ORDER_MANAGER(EnumSet.of(DASHBOARD_READ, CATALOG_READ, ORDERS_READ, ORDERS_WRITE, REFUNDS, INVENTORY_WRITE)),
    SUPPORT(EnumSet.of(DASHBOARD_READ, CATALOG_READ, ORDERS_READ, AFFILIATE_READ));

    private final Set<Permission> permissions;

    AdminRole(Set<Permission> permissions) {
        this.permissions = permissions;
    }

    public Set<Permission> permissions() {
        return permissions;
    }
}
