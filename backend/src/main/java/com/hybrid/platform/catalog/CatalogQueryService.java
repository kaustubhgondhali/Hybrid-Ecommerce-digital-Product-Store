package com.hybrid.platform.catalog;

import com.hybrid.platform.catalog.CatalogEnums.DeliveryKind;
import com.hybrid.platform.catalog.CatalogEnums.ProductStatus;
import com.hybrid.platform.catalog.CatalogEnums.ProductType;
import com.hybrid.platform.catalog.CatalogEnums.SellingMethod;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only catalog facts used by other modules (checkout pricing, delivery, affiliate).
 * Depends only on catalog repositories so it can be used without creating dependency cycles.
 */
@Service
public class CatalogQueryService {

    public record SellableVariant(Long productId, Long variantId, Storefront storefront, ProductType productType,
                                  SellingMethod sellingMethod, ProductStatus status, boolean variantActive,
                                  String title, String variantName, String sku, Long priceMinor,
                                  DeliveryKind deliveryKind, String imageUrl, String slug, String hsnSac,
                                  Integer gstRateBps) {

        public boolean purchasableIn(Storefront sf) {
            return storefront == sf && sellingMethod == SellingMethod.DIRECT && status == ProductStatus.PUBLISHED
                    && variantActive && priceMinor != null && priceMinor >= ProductRules.MIN_PRICE_MINOR;
        }

        /** Affiliate offers can sit in the cart for later partner purchase; direct items must be purchasable. */
        public boolean cartableIn(Storefront sf) {
            if (storefront != sf || status != ProductStatus.PUBLISHED || !variantActive) return false;
            if (sellingMethod == SellingMethod.AFFILIATE) return true;
            return purchasableIn(sf);
        }
    }

    public record ProductRef(Long id, Storefront storefront, ProductType productType, SellingMethod sellingMethod,
                             ProductStatus status, String title, DeliveryKind deliveryKind) {
    }

    private final ProductRepository products;
    private final ProductVariantRepository variants;

    public CatalogQueryService(ProductRepository products, ProductVariantRepository variants) {
        this.products = products;
        this.variants = variants;
    }

    @Transactional(readOnly = true)
    public Map<Long, SellableVariant> variants(Collection<Long> variantIds) {
        Map<Long, SellableVariant> result = new HashMap<>();
        if (variantIds.isEmpty()) {
            return result;
        }
        for (ProductVariant v : variants.findWithProductByIdIn(variantIds)) {
            Product p = v.getProduct();
            String image = p.getImages().isEmpty() ? null : p.getImages().get(0).thumbUrl();
            result.put(v.getId(), new SellableVariant(p.getId(), v.getId(), p.getStorefront(), p.getProductType(),
                    p.getSellingMethod(), p.getStatus(), v.isActive(), p.getTitle(), v.getName(), v.getSku(),
                    v.getPriceMinor(), p.getDeliveryKind(), image, p.getSlug(), p.getHsnSac(), p.getGstRateBps()));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Optional<ProductRef> product(Long productId) {
        return products.findById(productId).map(CatalogQueryService::ref);
    }

    @Transactional(readOnly = true)
    public ProductRef requireProduct(Storefront sf, Long productId) {
        return products.findByIdAndStorefront(productId, sf).map(CatalogQueryService::ref)
                .orElseThrow(() -> ApiException.notFound("PRODUCT_NOT_FOUND", "Product not found"));
    }

    /** After the first order or affiliate link, type/method/storefront become immutable. */
    @Transactional
    public void lockTypes(Collection<Long> productIds) {
        products.findAllById(productIds).forEach(Product::lockType);
    }

    private static ProductRef ref(Product p) {
        return new ProductRef(p.getId(), p.getStorefront(), p.getProductType(), p.getSellingMethod(), p.getStatus(),
                p.getTitle(), p.getDeliveryKind());
    }
}
