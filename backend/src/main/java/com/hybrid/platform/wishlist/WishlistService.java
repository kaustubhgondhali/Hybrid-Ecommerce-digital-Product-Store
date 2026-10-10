package com.hybrid.platform.wishlist;

import com.hybrid.platform.catalog.CatalogEnums.ProductStatus;
import com.hybrid.platform.catalog.CatalogPublicService;
import com.hybrid.platform.catalog.Product;
import com.hybrid.platform.catalog.ProductRepository;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Saved products per customer and storefront. Only published products of the same storefront can be saved. */
@Service
public class WishlistService {

    static final int MAX_ITEMS = 200;

    public record Status(boolean saved, long count) {
    }

    private final WishlistRepository items;
    private final ProductRepository products;
    private final CatalogPublicService catalog;

    public WishlistService(WishlistRepository items, ProductRepository products, CatalogPublicService catalog) {
        this.items = items;
        this.products = products;
        this.catalog = catalog;
    }

    @Transactional(readOnly = true)
    public List<CatalogPublicService.ProductCard> list(Long customerId, Storefront sf) {
        List<Long> ids = items.findByCustomerIdAndStorefrontOrderByCreatedAtDesc(customerId, sf).stream()
                .map(WishlistItem::getProductId).toList();
        return catalog.cards(sf, ids);
    }

    @Transactional(readOnly = true)
    public Status status(Long customerId, Storefront sf, String slug) {
        Product p = published(sf, slug);
        return new Status(items.existsByCustomerIdAndProductId(customerId, p.getId()),
                items.countByCustomerIdAndStorefront(customerId, sf));
    }

    @Transactional
    public Status add(Long customerId, Storefront sf, String slug) {
        Product p = published(sf, slug);
        if (!items.existsByCustomerIdAndProductId(customerId, p.getId())) {
            if (items.countByCustomerIdAndStorefront(customerId, sf) >= MAX_ITEMS) {
                throw ApiException.rule("WISHLIST_FULL", "Your wishlist is full. Remove something first.");
            }
            items.save(new WishlistItem(customerId, sf, p.getId()));
        }
        return new Status(true, items.countByCustomerIdAndStorefront(customerId, sf));
    }

    @Transactional
    public Status remove(Long customerId, Storefront sf, String slug) {
        products.findByStorefrontAndSlug(sf, slug).ifPresent(p -> items.deleteItem(customerId, p.getId()));
        return new Status(false, items.countByCustomerIdAndStorefront(customerId, sf));
    }

    private Product published(Storefront sf, String slug) {
        return products.findByStorefrontAndSlugAndStatus(sf, slug, ProductStatus.PUBLISHED)
                .orElseThrow(() -> ApiException.notFound("PRODUCT_NOT_FOUND", "Product not found"));
    }
}
