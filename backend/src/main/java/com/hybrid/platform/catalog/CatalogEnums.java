package com.hybrid.platform.catalog;

public final class CatalogEnums {

    private CatalogEnums() {
    }

    public enum ProductType { PHYSICAL, DIGITAL }

    public enum SellingMethod { DIRECT, AFFILIATE }

    public enum ProductStatus { DRAFT, PUBLISHED, ARCHIVED }

    public enum DeliveryKind { DOWNLOAD, LICENSE_KEY, DOWNLOAD_AND_LICENSE }
}
