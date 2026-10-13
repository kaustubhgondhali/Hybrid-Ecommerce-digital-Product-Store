package com.hybrid.platform.catalog;

import com.hybrid.platform.common.Storefront;

public final class CatalogEvents {

    private CatalogEvents() {
    }

    /** A product went live for the first time (re-publishing after an unpublish does not count as new). */
    public record ProductPublished(Storefront storefront, Long productId, String slug, String title) {
    }
}
