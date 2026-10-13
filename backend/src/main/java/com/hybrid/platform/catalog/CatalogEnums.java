package com.hybrid.platform.catalog;

public final class CatalogEnums {

    private CatalogEnums() {
    }

    public enum ProductType { PHYSICAL, DIGITAL }

    /**
     * DIRECT = own stock, DROPSHIP = supplier ships (physical only), AFFILIATE = bought on a partner site.
     * DIRECT and DROPSHIP are both paid for on this site.
     */
    public enum SellingMethod {
        DIRECT, DROPSHIP, AFFILIATE;

        public boolean onSite() {
            return this != AFFILIATE;
        }
    }

    public enum ProductStatus { DRAFT, PUBLISHED, ARCHIVED }

    public enum DeliveryKind { DOWNLOAD, LICENSE_KEY, DOWNLOAD_AND_LICENSE }
}
