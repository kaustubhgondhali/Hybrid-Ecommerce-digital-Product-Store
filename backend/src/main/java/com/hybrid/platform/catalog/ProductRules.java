package com.hybrid.platform.catalog;

import com.hybrid.platform.catalog.CatalogEnums.ProductType;
import com.hybrid.platform.catalog.CatalogEnums.SellingMethod;

import java.util.ArrayList;
import java.util.List;

/**
 * Publish-time rules for the four productType x sellingMethod combinations
 * (see docs/PRODUCT-BEHAVIOR-MATRIX.md section 2).
 */
public final class ProductRules {

    public static final long MIN_PRICE_MINOR = 100;

    private ProductRules() {
    }

    public record Context(boolean hasAffiliateLink, boolean digitalDeliveryReady) {
    }

    public static List<String> publishProblems(Product p, Context ctx) {
        List<String> problems = new ArrayList<>();
        if (p.getTitle() == null || p.getTitle().isBlank()) {
            problems.add("Title is required");
        }
        if (p.getImages().isEmpty()) {
            problems.add("At least one product image is required");
        }
        List<ProductVariant> active = p.activeVariants();
        if (active.isEmpty()) {
            problems.add("At least one active variant is required");
        }

        if (p.getSellingMethod() == SellingMethod.DIRECT) {
            for (ProductVariant v : active) {
                if (v.getPriceMinor() == null || v.getPriceMinor() < MIN_PRICE_MINOR) {
                    problems.add("Variant '" + v.getName() + "' needs a price of at least 1.00");
                }
                if (v.getCompareAtMinor() != null && v.getPriceMinor() != null && v.getCompareAtMinor() <= v.getPriceMinor()) {
                    problems.add("Variant '" + v.getName() + "' compare-at price must be higher than the price");
                }
            }
        } else {
            if (!ctx.hasAffiliateLink()) {
                problems.add("Affiliate products need an affiliate link");
            }
            if (active.size() > 1) {
                problems.add("Affiliate products have exactly one variant");
            }
        }

        if (p.getProductType() == ProductType.PHYSICAL && p.getSellingMethod() == SellingMethod.DIRECT) {
            for (ProductVariant v : active) {
                if (v.getSku() == null) {
                    problems.add("Variant '" + v.getName() + "' needs a SKU");
                }
                if (v.getWeightGrams() == null || v.getWeightGrams() <= 0) {
                    problems.add("Variant '" + v.getName() + "' needs a weight in grams");
                }
            }
        }

        if (p.getProductType() == ProductType.DIGITAL) {
            if (p.getDeliveryKind() == null) {
                problems.add("Digital products need a delivery type (download and/or license key)");
            } else if (p.getSellingMethod() == SellingMethod.DIRECT && !ctx.digitalDeliveryReady()) {
                problems.add("Upload at least one file and/or import license keys before publishing");
            }
        }
        return problems;
    }
}
