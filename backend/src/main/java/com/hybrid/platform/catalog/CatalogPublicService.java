package com.hybrid.platform.catalog;

import com.hybrid.platform.affiliate.AffiliateService;
import com.hybrid.platform.catalog.CatalogEnums.ProductStatus;
import com.hybrid.platform.catalog.CatalogEnums.ProductType;
import com.hybrid.platform.catalog.CatalogEnums.SellingMethod;
import com.hybrid.platform.common.AppProperties;
import com.hybrid.platform.common.PageResponse;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.commerce.inventory.InventoryService;
import com.hybrid.platform.delivery.DeliveryReadinessService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/** Public storefront read model. Prices, availability and affiliate links all come from the backend. */
@Service
public class CatalogPublicService {

    public record ProductCard(Long id, String slug, String title, String summary, String productType,
                              String sellingMethod, String imageUrl, Long priceMinor, Long compareAtMinor,
                              Boolean inStock, String merchantName, String goUrl) {
    }

    public record VariantView(Long id, String name, String sku, Long priceMinor, Long compareAtMinor, boolean available) {
    }

    public record ImageView(String url, String alt) {
    }

    public record CategoryView(Long id, String name, String slug) {
    }

    public record AffiliateOffer(String merchantName, Long displayPriceMinor, Instant priceCheckedAt, String disclosure,
                                 String goUrl) {
    }

    public record ProductDetail(Long id, String slug, String title, String summary, String description, String brand,
                                CategoryView category, String productType, String sellingMethod, String digitalFormat,
                                String deliveryKind, List<ImageView> images, List<VariantView> variants,
                                AffiliateOffer affiliate, String currency) {
    }

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final InventoryService inventory;
    private final AffiliateService affiliate;
    private final DeliveryReadinessService delivery;
    private final ProductSearch search;
    private final AppProperties props;

    public CatalogPublicService(ProductRepository products, CategoryRepository categories, InventoryService inventory,
                                AffiliateService affiliate, DeliveryReadinessService delivery, ProductSearch search,
                                AppProperties props) {
        this.products = products;
        this.categories = categories;
        this.inventory = inventory;
        this.affiliate = affiliate;
        this.delivery = delivery;
        this.search = search;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductCard> list(Storefront sf, String q, String categorySlug, ProductType type,
                                          SellingMethod method, String sort, int page, int size) {
        if (props.affiliateOnly()) {
            if (method == SellingMethod.DIRECT) {
                return PageResponse.of(Page.empty(), p -> null);
            }
            method = SellingMethod.AFFILIATE;
        }
        Long categoryId = null;
        if (categorySlug != null && !categorySlug.isBlank()) {
            categoryId = categories.findByStorefrontAndSlug(sf, categorySlug).filter(Category::isActive)
                    .map(Category::getId).orElse(-1L);
        }
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase(Locale.ROOT).replace("%", "").replace("_", "") + "%";
        Sort order = "title".equals(sort) ? Sort.by("title") : Sort.by(Sort.Direction.DESC, "publishedAt");
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 48), order);
        boolean unknownCategory = categoryId != null && categoryId < 0;
        Optional<List<Long>> ranked = like == null || unknownCategory || "title".equals(sort) ? Optional.empty()
                : search.rankedIds(sf, q.trim(), categoryId, type, method);
        Page<Product> result = ranked.isPresent() ? rankedPage(ranked.get(), pageable)
                : products.search(sf, ProductStatus.PUBLISHED, like, categoryId, type, method, pageable);
        List<Product> list = result.getContent();
        Map<Long, AffiliateService.OfferView> offers = affiliate.offers(
                list.stream().filter(p -> p.getSellingMethod() == SellingMethod.AFFILIATE).map(Product::getId).toList());
        Map<Long, Integer> stock = inventory.availability(list.stream().filter(Product::isPhysicalDirect)
                .flatMap(p -> p.activeVariants().stream()).map(ProductVariant::getId).toList());
        return PageResponse.of(result, p -> card(p, offers.get(p.getId()), stock));
    }

    private Page<Product> rankedPage(List<Long> ids, PageRequest pageable) {
        int from = (int) Math.min(pageable.getOffset(), ids.size());
        int to = Math.min(from + pageable.getPageSize(), ids.size());
        List<Long> slice = ids.subList(from, to);
        Map<Long, Product> byId = new HashMap<>();
        products.findAllById(slice).forEach(p -> byId.put(p.getId(), p));
        List<Product> content = slice.stream().map(byId::get).filter(Objects::nonNull).toList();
        return new org.springframework.data.domain.PageImpl<>(content, pageable, ids.size());
    }

    /** Cards for the given published products of this storefront, in the order of {@code ids}. */
    @Transactional(readOnly = true)
    public List<ProductCard> cards(Storefront sf, List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        Map<Long, Product> byId = new HashMap<>();
        products.findAllById(ids).stream()
                .filter(p -> p.getStorefront() == sf && p.getStatus() == ProductStatus.PUBLISHED)
                .forEach(p -> byId.put(p.getId(), p));
        List<Product> list = ids.stream().map(byId::get).filter(Objects::nonNull).toList();
        Map<Long, AffiliateService.OfferView> offers = affiliate.offers(
                list.stream().filter(p -> p.getSellingMethod() == SellingMethod.AFFILIATE).map(Product::getId).toList());
        Map<Long, Integer> stock = inventory.availability(list.stream().filter(Product::isPhysicalDirect)
                .flatMap(p -> p.activeVariants().stream()).map(ProductVariant::getId).toList());
        return list.stream().map(p -> card(p, offers.get(p.getId()), stock)).toList();
    }

    @Transactional(readOnly = true)
    public ProductDetail detail(Storefront sf, String slug) {
        Product p = products.findByStorefrontAndSlugAndStatus(sf, slug, ProductStatus.PUBLISHED)
                .orElseThrow(() -> ApiException.notFound("PRODUCT_NOT_FOUND", "Product not found"));
        if (props.affiliateOnly() && p.getSellingMethod() != SellingMethod.AFFILIATE) {
            throw ApiException.notFound("PRODUCT_NOT_FOUND", "Product not found");
        }
        List<ProductVariant> active = p.activeVariants();
        Map<Long, Integer> stock = p.isPhysicalDirect()
                ? inventory.availability(active.stream().map(ProductVariant::getId).toList()) : Map.of();
        boolean digitalAvailable = p.getProductType() != ProductType.DIGITAL || !p.isDirect()
                || !DeliveryReadinessService.needsLicense(p.getDeliveryKind()) || delivery.availableLicenses(p.getId()) > 0;
        AffiliateOffer offer = null;
        AffiliateService.OfferView affiliateOffer = null;
        if (p.getSellingMethod() == SellingMethod.AFFILIATE) {
            affiliateOffer = affiliate.offers(List.of(p.getId())).get(p.getId());
            if (affiliateOffer != null) {
                offer = new AffiliateOffer(affiliateOffer.merchantName(), affiliateOffer.displayPriceMinor(),
                        affiliateOffer.priceCheckedAt(), affiliateOffer.disclosure(), affiliateOffer.goUrl());
            }
        }
        final AffiliateService.OfferView ao = affiliateOffer;
        List<VariantView> variants = p.isDirect()
                ? active.stream().map(v -> new VariantView(v.getId(), v.getName(), v.getSku(), v.getPriceMinor(),
                v.getCompareAtMinor(), p.isPhysicalDirect() ? stock.getOrDefault(v.getId(), 0) > 0 : digitalAvailable)).toList()
                : active.stream().map(v -> new VariantView(v.getId(), v.getName(), null,
                ao == null ? null : ao.displayPriceMinor(), null, ao != null)).toList();
        CategoryView category = p.getCategoryId() == null ? null : categories.findById(p.getCategoryId())
                .filter(Category::isActive).map(c -> new CategoryView(c.getId(), c.getName(), c.getSlug())).orElse(null);
        return new ProductDetail(p.getId(), p.getSlug(), p.getTitle(), p.getSummary(), p.getDescription(), p.getBrand(),
                category, p.getProductType().name(), p.getSellingMethod().name(), p.getDigitalFormat(),
                p.getDeliveryKind() == null ? null : p.getDeliveryKind().name(),
                p.getImages().stream().map(i -> new ImageView(i.url(), i.getAltText() == null ? p.getTitle() : i.getAltText())).toList(),
                variants, offer, "INR");
    }

    @Transactional(readOnly = true)
    public List<CategoryView> categories(Storefront sf) {
        return categories.findByStorefrontAndActiveTrueOrderBySortOrderAscNameAsc(sf).stream()
                .map(c -> new CategoryView(c.getId(), c.getName(), c.getSlug())).toList();
    }

    private ProductCard card(Product p, AffiliateService.OfferView offer, Map<Long, Integer> stock) {
        String image = p.getImages().isEmpty() ? null : p.getImages().get(0).thumbUrl();
        if (p.getSellingMethod() == SellingMethod.AFFILIATE) {
            return new ProductCard(p.getId(), p.getSlug(), p.getTitle(), p.getSummary(), p.getProductType().name(),
                    p.getSellingMethod().name(), image, offer == null ? null : offer.displayPriceMinor(), null, null,
                    offer == null ? null : offer.merchantName(), offer == null ? null : offer.goUrl());
        }
        List<ProductVariant> active = p.activeVariants();
        ProductVariant cheapest = active.stream().filter(v -> v.getPriceMinor() != null)
                .min(Comparator.comparing(ProductVariant::getPriceMinor)).orElse(null);
        Boolean inStock = p.isPhysicalDirect()
                ? active.stream().anyMatch(v -> stock.getOrDefault(v.getId(), 0) > 0) : Boolean.TRUE;
        return new ProductCard(p.getId(), p.getSlug(), p.getTitle(), p.getSummary(), p.getProductType().name(),
                p.getSellingMethod().name(), image, cheapest == null ? null : cheapest.getPriceMinor(),
                cheapest == null ? null : cheapest.getCompareAtMinor(), inStock, null, null);
    }
}
