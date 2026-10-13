package com.hybrid.platform.catalog;

import com.hybrid.platform.affiliate.AffiliateService;
import com.hybrid.platform.catalog.CatalogEnums.DeliveryKind;
import com.hybrid.platform.catalog.CatalogEnums.ProductStatus;
import com.hybrid.platform.catalog.CatalogEnums.ProductType;
import com.hybrid.platform.catalog.CatalogEnums.SellingMethod;
import com.hybrid.platform.common.AppProperties;
import com.hybrid.platform.common.PageResponse;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.audit.AuditService;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.commerce.inventory.InventoryService;
import com.hybrid.platform.commerce.settings.SettingsService;
import com.hybrid.platform.seo.SeoRedirectService;
import com.hybrid.platform.delivery.DeliveryReadinessService;
import com.hybrid.platform.security.AdminPrincipal;
import com.hybrid.platform.storage.FileStorage;
import com.hybrid.platform.storage.FileTypes;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;

@Service
public class CatalogAdminService {

    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    static final long MAX_VIDEO_BYTES = 100L * 1024 * 1024;
    static final int MAX_VIDEOS = 3;

    public record ProductInput(ProductType productType, SellingMethod sellingMethod, String title, String slug,
                               String summary, String description, String brand, Long categoryId,
                               String digitalFormat, DeliveryKind deliveryKind,
                               Long priceMinor, Long compareAtMinor, String sku, Integer weightGrams,
                               String metaTitle, String metaDescription, String focusKeyword, Boolean noindex,
                               String hsnSac, Integer gstRateBps,
                               String highlights, String pros, String cons, String verdict, Integer expertRating,
                               String specs) {
    }

    /** Deal content limits: one item per line. */
    static final int MAX_LIST_ITEMS = 8;
    static final int MAX_ITEM_LENGTH = 160;

    public record VariantInput(String name, String sku, Long priceMinor, Long compareAtMinor, Integer weightGrams,
                               Boolean active, Integer sortOrder) {
    }

    public record AdminVariantView(Long id, String name, String sku, Long priceMinor, Long compareAtMinor,
                                   Integer weightGrams, boolean active, boolean defaultVariant, Integer available) {
    }

    public record AdminImageView(Long id, String url, String thumbUrl, String alt) {
    }

    public record AdminVideoView(Long id, String url, String posterUrl, String title, Integer durationSeconds,
                                 Integer width, Integer height, long sizeBytes) {
    }

    public record AdminProductSummary(Long id, String title, String slug, String productType, String sellingMethod,
                                      String status, String imageUrl, Long priceMinor, Instant updatedAt) {
    }

    public record AdminProductView(Long id, String storefront, String productType, String sellingMethod, String status,
                                   String title, String slug, String summary, String description, String brand,
                                   Long categoryId, String digitalFormat, String deliveryKind, boolean typeLocked,
                                   Instant publishedAt, Instant updatedAt, long version,
                                   List<AdminVariantView> variants, List<AdminImageView> images, List<AdminVideoView> videos,
                                   AffiliateService.LinkView affiliateLink, List<AffiliateService.LinkView> affiliateLinks,
                                   List<String> publishProblems,
                                   String metaTitle, String metaDescription, String focusKeyword, boolean noindex,
                                   String hsnSac, Integer gstRateBps,
                                   String highlights, String pros, String cons, String verdict, Integer expertRating,
                                   String specs) {
    }

    public record CategoryInput(String name, String slug, Integer sortOrder, Boolean active, String description,
                                String metaTitle, String metaDescription) {
    }

    public record AdminCategoryView(Long id, String name, String slug, int sortOrder, boolean active, String description,
                                    String metaTitle, String metaDescription) {
    }

    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final CategoryRepository categories;
    private final InventoryService inventory;
    private final AffiliateService affiliate;
    private final DeliveryReadinessService delivery;
    private final FileStorage storage;
    private final ImageOptimizer images;
    private final SeoRedirectService redirects;
    private final AuditService audit;
    private final SettingsService settings;
    private final org.springframework.context.ApplicationEventPublisher events;

    public CatalogAdminService(ProductRepository products, ProductVariantRepository variants,
                               CategoryRepository categories, InventoryService inventory, AffiliateService affiliate,
                               DeliveryReadinessService delivery, FileStorage storage, ImageOptimizer images,
                               SeoRedirectService redirects, AuditService audit, SettingsService settings,
                               org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
        this.products = products;
        this.variants = variants;
        this.categories = categories;
        this.inventory = inventory;
        this.affiliate = affiliate;
        this.delivery = delivery;
        this.storage = storage;
        this.images = images;
        this.redirects = redirects;
        this.audit = audit;
        this.settings = settings;
    }

    /** GST slabs in basis points. */
    public static final Set<Integer> GST_RATES = Set.of(0, 25, 300, 500, 1200, 1800, 2800);

    // ------------------------------------------------------------------ products

    @Transactional(readOnly = true)
    public PageResponse<AdminProductSummary> list(Storefront sf, String q, ProductStatus status, ProductType type,
                                                  SellingMethod method, int page, int size) {
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        return PageResponse.of(products.search(sf, status, like, null, type, method,
                        PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "updatedAt"))),
                p -> new AdminProductSummary(p.getId(), p.getTitle(), p.getSlug(), p.getProductType().name(),
                        p.getSellingMethod().name(), p.getStatus().name(),
                        p.getImages().isEmpty() ? null : p.getImages().get(0).thumbUrl(),
                        p.activeVariants().stream().map(ProductVariant::getPriceMinor).filter(Objects::nonNull).min(Long::compare).orElse(null),
                        p.getUpdatedAt()));
    }

    @Transactional(readOnly = true)
    public AdminProductView get(Storefront sf, Long id) {
        return view(require(sf, id));
    }

    @Transactional
    public AdminProductView create(AdminPrincipal admin, ProductInput in) {
        if (in.productType() == null || in.sellingMethod() == null) {
            throw ApiException.badRequest("TYPE_REQUIRED", "Choose a product type and a selling method");
        }
        Storefront sf = admin.storefront();
        SellingMethod method = requireAllowedMethod(sf, in.sellingMethod(), in.productType());
        requireText(in.title(), "Title");
        Product p = new Product(sf, in.productType(), method, in.title().trim(),
                uniqueSlug(sf, in.slug() == null || in.slug().isBlank() ? in.title() : in.slug(), null));
        applyDetails(sf, p, in);
        ProductVariant v = new ProductVariant("Default", true, 0);
        if (p.isOnSite()) {
            v.setPriceMinor(validPrice(in.priceMinor()));
            v.setCompareAtMinor(validPrice(in.compareAtMinor()));
        }
        if (p.isOnSite() && p.getProductType() == ProductType.PHYSICAL) {
            v.setSku(requireUniqueSku(in.sku(), null));
            v.setWeightGrams(in.weightGrams());
        }
        p.addVariant(v);
        p = products.save(p);
        syncInventory(p);
        audit.record(admin, "PRODUCT_CREATED", "Product", p.getId(), p.getProductType() + "+" + p.getSellingMethod() + " " + p.getTitle());
        return view(p);
    }

    @Transactional
    public AdminProductView update(AdminPrincipal admin, Long id, ProductInput in, Long expectedVersion) {
        Product p = require(admin.storefront(), id);
        if (expectedVersion != null && expectedVersion != p.getVersion()) {
            throw ApiException.conflict("CONCURRENT_MODIFICATION", "This product was changed by someone else. Reload and try again.");
        }
        if (in.sellingMethod() != null || in.productType() != null) {
            SellingMethod method = in.sellingMethod() != null ? in.sellingMethod() : p.getSellingMethod();
            ProductType type = in.productType() != null ? in.productType() : p.getProductType();
            if (method != p.getSellingMethod() || type != p.getProductType()) requireAllowedMethod(admin.storefront(), method, type);
        }
        boolean typeChange = (in.productType() != null && in.productType() != p.getProductType())
                || (in.sellingMethod() != null && in.sellingMethod() != p.getSellingMethod());
        if (typeChange) {
            if (p.isTypeLocked()) {
                throw ApiException.rule("TYPE_LOCKED", "Type and selling method cannot change after the product was ordered or linked. Create a new product instead.");
            }
            if (p.getStatus() == ProductStatus.PUBLISHED) {
                throw ApiException.rule("UNPUBLISH_FIRST", "Unpublish the product before changing its type or selling method");
            }
            if (in.productType() != null) p.setProductType(in.productType());
            if (in.sellingMethod() != null) p.setSellingMethod(in.sellingMethod());
            if (!p.isOnSite()) {
                p.getVariants().forEach(v -> { if (!v.isDefaultVariant()) v.setActive(false); v.setPriceMinor(null); v.setCompareAtMinor(null); });
            }
        }
        if (in.title() != null) {
            requireText(in.title(), "Title");
            p.setTitle(in.title().trim());
        }
        if (in.slug() != null && !in.slug().isBlank()) {
            String oldSlug = p.getSlug();
            p.setSlug(uniqueSlug(admin.storefront(), in.slug(), p.getId()));
            if (!oldSlug.equals(p.getSlug()) && p.getPublishedAt() != null) {
                String base = "/" + admin.storefront().path() + "/p/";
                redirects.autoRedirect(admin.storefront(), base + oldSlug, base + p.getSlug());
            }
        }
        applyDetails(admin.storefront(), p, in);
        syncInventory(p);
        audit.record(admin, "PRODUCT_UPDATED", "Product", id, typeChange ? "type/method changed" : null);
        return view(p);
    }

    @Transactional
    public AdminProductView publish(AdminPrincipal admin, Long id) {
        Product p = require(admin.storefront(), id);
        List<String> problems = problems(p);
        if (!problems.isEmpty()) {
            throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "PUBLISH_BLOCKED",
                    "This product is not ready to publish", Map.of("problems", problems));
        }
        p.setStatus(ProductStatus.PUBLISHED);
        if (p.getPublishedAt() == null) {
            p.setPublishedAt(Instant.now());
            events.publishEvent(new CatalogEvents.ProductPublished(p.getStorefront(), p.getId(), p.getSlug(), p.getTitle()));
        }
        audit.record(admin, "PRODUCT_PUBLISHED", "Product", id, null);
        return view(p);
    }

    @Transactional
    public AdminProductView changeStatus(AdminPrincipal admin, Long id, ProductStatus status) {
        if (status == ProductStatus.PUBLISHED) {
            return publish(admin, id);
        }
        Product p = require(admin.storefront(), id);
        p.setStatus(status);
        audit.record(admin, "PRODUCT_" + status.name(), "Product", id, null);
        return view(p);
    }

    // ------------------------------------------------------------------ variants

    @Transactional
    public AdminProductView addVariant(AdminPrincipal admin, Long productId, VariantInput in) {
        Product p = require(admin.storefront(), productId);
        if (!p.isOnSite()) {
            throw ApiException.rule("AFFILIATE_SINGLE_VARIANT", "Affiliate products have exactly one variant");
        }
        requireText(in.name(), "Variant name");
        ProductVariant v = new ProductVariant(in.name().trim(), false, in.sortOrder() == null ? p.getVariants().size() : in.sortOrder());
        applyVariant(p, v, in);
        p.addVariant(v);
        products.saveAndFlush(p);
        syncInventory(p);
        audit.record(admin, "VARIANT_ADDED", "Product", productId, v.getName());
        return view(p);
    }

    @Transactional
    public AdminProductView updateVariant(AdminPrincipal admin, Long productId, Long variantId, VariantInput in) {
        Product p = require(admin.storefront(), productId);
        ProductVariant v = p.getVariants().stream().filter(x -> x.getId().equals(variantId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("VARIANT_NOT_FOUND", "Variant not found"));
        if (in.name() != null) {
            requireText(in.name(), "Variant name");
            v.setName(in.name().trim());
        }
        if (in.sortOrder() != null) v.setSortOrder(in.sortOrder());
        if (in.active() != null) {
            if (!in.active() && p.activeVariants().size() <= 1 && v.isActive()) {
                throw ApiException.rule("LAST_VARIANT", "A product needs at least one active variant");
            }
            v.setActive(in.active());
        }
        applyVariant(p, v, in);
        syncInventory(p);
        audit.record(admin, "VARIANT_UPDATED", "Variant", variantId, null);
        return view(p);
    }

    private void applyVariant(Product p, ProductVariant v, VariantInput in) {
        if (p.isOnSite()) {
            if (in.priceMinor() != null) v.setPriceMinor(validPrice(in.priceMinor()));
            if (in.compareAtMinor() != null) v.setCompareAtMinor(in.compareAtMinor() <= 0 ? null : validPrice(in.compareAtMinor()));
        }
        if (p.getProductType() == ProductType.PHYSICAL) {
            if (in.sku() != null) v.setSku(requireUniqueSku(in.sku(), v.getId()));
            if (in.weightGrams() != null) {
                if (in.weightGrams() <= 0) throw ApiException.rule("INVALID_WEIGHT", "Weight must be positive");
                v.setWeightGrams(in.weightGrams());
            }
        } else if (in.sku() != null) {
            v.setSku(requireUniqueSku(in.sku(), v.getId()));
        }
    }

    // ------------------------------------------------------------------ images

    @Transactional
    public AdminProductView uploadImage(AdminPrincipal admin, Long productId, MultipartFile file, String alt) throws IOException {
        Product p = require(admin.storefront(), productId);
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("FILE_REQUIRED", "Choose an image to upload");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw ApiException.rule("FILE_TOO_LARGE", "Images must be 5 MB or smaller");
        }
        if (p.getImages().size() >= 10) {
            throw ApiException.rule("TOO_MANY_IMAGES", "A product can have at most 10 images");
        }
        try (InputStream in = file.getInputStream()) {
            byte[] header = in.readNBytes(16);
            String ext = FileTypes.requireImage(header);
            FileStorage.StoredFile stored = storage.store(FileStorage.Area.PUBLIC,
                    new SequenceInputStream(new ByteArrayInputStream(header), in), ext, MAX_IMAGE_BYTES);
            ProductImage image = new ProductImage(stored.key(), alt == null || alt.isBlank() ? null : trimTo(alt, 250), p.getImages().size());
            ImageOptimizer.Result r = images.renditions(stored.key());
            image.setRenditions(r.thumbKey(), r.largeKey(), r.width(), r.height());
            p.addImage(image);
        }
        audit.record(admin, "PRODUCT_IMAGE_ADDED", "Product", productId, null);
        return view(products.saveAndFlush(p));
    }

    @Transactional
    public AdminProductView updateImageAlt(AdminPrincipal admin, Long productId, Long imageId, String alt) {
        Product p = require(admin.storefront(), productId);
        ProductImage img = p.getImages().stream().filter(i -> i.getId().equals(imageId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("IMAGE_NOT_FOUND", "Image not found"));
        img.setAltText(alt == null || alt.isBlank() ? null : trimTo(alt, 250));
        audit.record(admin, "PRODUCT_IMAGE_ALT_UPDATED", "Product", productId, null);
        return view(p);
    }

    @Transactional
    public AdminProductView deleteImage(AdminPrincipal admin, Long productId, Long imageId) {
        Product p = require(admin.storefront(), productId);
        ProductImage img = p.getImages().stream().filter(i -> i.getId().equals(imageId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("IMAGE_NOT_FOUND", "Image not found"));
        p.getImages().remove(img);
        storage.delete(FileStorage.Area.PUBLIC, img.getStorageKey());
        if (img.getThumbKey() != null) storage.delete(FileStorage.Area.PUBLIC, img.getThumbKey());
        if (img.getLargeKey() != null) storage.delete(FileStorage.Area.PUBLIC, img.getLargeKey());
        audit.record(admin, "PRODUCT_IMAGE_REMOVED", "Product", productId, null);
        return view(p);
    }

    /** Moves an image to the front: the first image is the one shown on product cards, in search and when shared. */
    @Transactional
    public AdminProductView makeMainImage(AdminPrincipal admin, Long productId, Long imageId) {
        Product p = require(admin.storefront(), productId);
        ProductImage main = p.getImages().stream().filter(i -> i.getId().equals(imageId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("IMAGE_NOT_FOUND", "Image not found"));
        List<ProductImage> ordered = new ArrayList<>(p.getImages());
        ordered.remove(main);
        ordered.add(0, main);
        for (int i = 0; i < ordered.size(); i++) ordered.get(i).setSortOrder(i);
        p.getImages().sort(Comparator.comparingInt(ProductImage::getSortOrder));
        audit.record(admin, "PRODUCT_IMAGE_MAIN", "Product", productId, String.valueOf(imageId));
        return view(p);
    }

    // ------------------------------------------------------------------ videos

    /**
     * Adds a short video (MP4 or WebM, checked by its content) to the product gallery. The poster frame, duration and
     * size are measured by the admin's browser; they are only used for display and search results.
     */
    @Transactional
    public AdminProductView uploadVideo(AdminPrincipal admin, Long productId, MultipartFile file, MultipartFile poster,
                                        String title, Integer durationSeconds, Integer width, Integer height) throws IOException {
        Product p = require(admin.storefront(), productId);
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("FILE_REQUIRED", "Choose a video to upload");
        }
        if (file.getSize() > MAX_VIDEO_BYTES) {
            throw ApiException.rule("FILE_TOO_LARGE", "Videos must be 100 MB or smaller");
        }
        if (p.getVideos().size() >= MAX_VIDEOS) {
            throw ApiException.rule("TOO_MANY_VIDEOS", "A product can have at most " + MAX_VIDEOS + " videos");
        }
        String posterKey = null;
        if (poster != null && !poster.isEmpty()) {
            if (poster.getSize() > MAX_IMAGE_BYTES) {
                throw ApiException.rule("FILE_TOO_LARGE", "The poster image must be 5 MB or smaller");
            }
            try (InputStream in = poster.getInputStream()) {
                byte[] header = in.readNBytes(16);
                String ext = FileTypes.requireImage(header);
                posterKey = storage.store(FileStorage.Area.PUBLIC, new SequenceInputStream(new ByteArrayInputStream(header), in),
                        ext, MAX_IMAGE_BYTES).key();
            }
        }
        try (InputStream in = file.getInputStream()) {
            byte[] header = in.readNBytes(16);
            String ext = FileTypes.requireVideo(header);
            FileStorage.StoredFile stored = storage.store(FileStorage.Area.PUBLIC,
                    new SequenceInputStream(new ByteArrayInputStream(header), in), ext, MAX_VIDEO_BYTES);
            p.addVideo(new ProductVideo(stored.key(), posterKey, title == null || title.isBlank() ? null : trimTo(title.strip(), 200),
                    within(durationSeconds, 1, 36_000), within(width, 1, 10_000), within(height, 1, 10_000),
                    stored.sizeBytes(), p.getVideos().size()));
        } catch (RuntimeException | IOException e) {
            if (posterKey != null) storage.delete(FileStorage.Area.PUBLIC, posterKey);
            throw e;
        }
        audit.record(admin, "PRODUCT_VIDEO_ADDED", "Product", productId, null);
        return view(products.saveAndFlush(p));
    }

    @Transactional
    public AdminProductView updateVideoTitle(AdminPrincipal admin, Long productId, Long videoId, String title) {
        Product p = require(admin.storefront(), productId);
        ProductVideo v = video(p, videoId);
        v.setTitle(title == null || title.isBlank() ? null : trimTo(title.strip(), 200));
        audit.record(admin, "PRODUCT_VIDEO_UPDATED", "Product", productId, null);
        return view(p);
    }

    @Transactional
    public AdminProductView deleteVideo(AdminPrincipal admin, Long productId, Long videoId) {
        Product p = require(admin.storefront(), productId);
        ProductVideo v = video(p, videoId);
        p.getVideos().remove(v);
        storage.delete(FileStorage.Area.PUBLIC, v.getStorageKey());
        if (v.getPosterKey() != null) storage.delete(FileStorage.Area.PUBLIC, v.getPosterKey());
        audit.record(admin, "PRODUCT_VIDEO_REMOVED", "Product", productId, null);
        return view(p);
    }

    private static ProductVideo video(Product p, Long videoId) {
        return p.getVideos().stream().filter(v -> v.getId().equals(videoId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("VIDEO_NOT_FOUND", "Video not found"));
    }

    private static Integer within(Integer v, int min, int max) {
        return v == null || v < min || v > max ? null : v;
    }

    // ------------------------------------------------------------------ categories

    @Transactional(readOnly = true)
    public List<AdminCategoryView> listCategories(Storefront sf) {
        return categories.findByStorefrontOrderBySortOrderAscNameAsc(sf).stream().map(CatalogAdminService::view).toList();
    }

    @Transactional
    public AdminCategoryView createCategory(AdminPrincipal admin, CategoryInput in) {
        requireText(in.name(), "Category name");
        String slug = slugify(in.slug() == null || in.slug().isBlank() ? in.name() : in.slug());
        if (categories.existsByStorefrontAndSlug(admin.storefront(), slug)) {
            throw ApiException.conflict("CATEGORY_EXISTS", "A category with this slug already exists");
        }
        Category c = new Category(admin.storefront(), in.name().trim(), slug, in.sortOrder() == null ? 0 : in.sortOrder());
        applyCategorySeo(c, in);
        c = categories.save(c);
        audit.record(admin, "CATEGORY_CREATED", "Category", c.getId(), c.getName());
        return view(c);
    }

    @Transactional
    public AdminCategoryView updateCategory(AdminPrincipal admin, Long id, CategoryInput in) {
        Category c = categories.findByIdAndStorefront(id, admin.storefront())
                .orElseThrow(() -> ApiException.notFound("CATEGORY_NOT_FOUND", "Category not found"));
        if (in.name() != null) { requireText(in.name(), "Category name"); c.setName(in.name().trim()); }
        if (in.slug() != null && !in.slug().isBlank()) {
            String slug = slugify(in.slug());
            if (!slug.equals(c.getSlug()) && categories.existsByStorefrontAndSlug(admin.storefront(), slug)) {
                throw ApiException.conflict("CATEGORY_EXISTS", "A category with this slug already exists");
            }
            if (!slug.equals(c.getSlug())) {
                String base = "/" + admin.storefront().path() + "/c/";
                redirects.autoRedirect(admin.storefront(), base + c.getSlug(), base + slug);
            }
            c.setSlug(slug);
        }
        if (in.sortOrder() != null) c.setSortOrder(in.sortOrder());
        if (in.active() != null) c.setActive(in.active());
        applyCategorySeo(c, in);
        audit.record(admin, "CATEGORY_UPDATED", "Category", id, null);
        return view(c);
    }

    @Transactional
    public void deleteCategory(AdminPrincipal admin, Long id) {
        Category c = categories.findByIdAndStorefront(id, admin.storefront())
                .orElseThrow(() -> ApiException.notFound("CATEGORY_NOT_FOUND", "Category not found"));
        if (products.existsByCategoryId(id)) {
            throw ApiException.conflict("CATEGORY_IN_USE", "Move or remove the products in this category first, or deactivate it");
        }
        categories.delete(c);
        audit.record(admin, "CATEGORY_DELETED", "Category", id, c.getName());
    }

    // ------------------------------------------------------------------ helpers

    private Product require(Storefront sf, Long id) {
        return products.findByIdAndStorefront(id, sf)
                .orElseThrow(() -> ApiException.notFound("PRODUCT_NOT_FOUND", "Product not found"));
    }

    private static void applyCategorySeo(Category c, CategoryInput in) {
        if (in.description() != null) c.setDescription(trimTo(in.description(), 5000));
        if (in.metaTitle() != null) c.setMetaTitle(trimTo(in.metaTitle(), 200));
        if (in.metaDescription() != null) c.setMetaDescription(trimTo(in.metaDescription(), 320));
    }

    private void applyDetails(Storefront sf, Product p, ProductInput in) {
        if (in.metaTitle() != null) p.setMetaTitle(trimTo(in.metaTitle(), 200));
        if (in.metaDescription() != null) p.setMetaDescription(trimTo(in.metaDescription(), 320));
        if (in.focusKeyword() != null) p.setFocusKeyword(trimTo(in.focusKeyword(), 100));
        if (in.noindex() != null) p.setNoindex(in.noindex());
        if (in.hsnSac() != null) p.setHsnSac(SettingsService.hsn(in.hsnSac()));
        if (in.gstRateBps() != null) {
            if (in.gstRateBps() < 0) {
                p.setGstRateBps(null);
            } else if (!GST_RATES.contains(in.gstRateBps())) {
                throw ApiException.badRequest("INVALID_GST_RATE", "GST rate must be one of 0%, 0.25%, 3%, 5%, 12%, 18%, 28%");
            } else {
                p.setGstRateBps(in.gstRateBps());
            }
        }
        if (in.summary() != null) p.setSummary(trimTo(in.summary(), 500));
        if (in.description() != null) p.setDescription(trimTo(in.description(), 20000));
        if (in.brand() != null) p.setBrand(trimTo(in.brand(), 120));
        if (in.highlights() != null) p.setHighlights(lines(in.highlights(), "Highlights"));
        if (in.pros() != null) p.setPros(lines(in.pros(), "Pros"));
        if (in.cons() != null) p.setCons(lines(in.cons(), "Cons"));
        if (in.verdict() != null) p.setVerdict(trimTo(in.verdict(), 600));
        if (in.specs() != null) p.setSpecs(specs(in.specs()));
        if (in.expertRating() != null) {
            if (in.expertRating() <= 0) {
                p.setExpertRating(null);
            } else if (in.expertRating() < 10 || in.expertRating() > 50 || in.expertRating() % 5 != 0) {
                throw ApiException.badRequest("INVALID_RATING", "Rating must be between 1 and 5 stars, in half stars");
            } else {
                p.setExpertRating(in.expertRating());
            }
        }
        if (in.categoryId() != null) {
            if (in.categoryId() <= 0) {
                p.setCategoryId(null);
            } else {
                categories.findByIdAndStorefront(in.categoryId(), sf)
                        .orElseThrow(() -> ApiException.rule("CATEGORY_NOT_FOUND", "Category not found in this storefront"));
                p.setCategoryId(in.categoryId());
            }
        }
        if (p.getProductType() == ProductType.DIGITAL) {
            if (in.digitalFormat() != null) p.setDigitalFormat(trimTo(in.digitalFormat(), 60));
            if (in.deliveryKind() != null) p.setDeliveryKind(in.deliveryKind());
            if (p.getDeliveryKind() == null && p.isOnSite()) p.setDeliveryKind(DeliveryKind.DOWNLOAD);
        } else {
            p.setDigitalFormat(null);
            p.setDeliveryKind(null);
        }
    }

    /** Normalises a one-item-per-line list; null when empty. */
    static final int MAX_SPECS = 30;

    /** "Name: Value" lines (a leading bullet is ignored); at most 30, names up to 40 and values up to 160 characters. */
    static String specs(String raw) {
        List<String> out = new ArrayList<>();
        for (String line : raw.lines().map(String::trim).map(s -> s.replaceFirst("^[-*\u2022]\\s*", "")).filter(s -> !s.isEmpty()).toList()) {
            int colon = line.indexOf(':');
            String name = colon < 0 ? "" : line.substring(0, colon).trim();
            String value = colon < 0 ? "" : line.substring(colon + 1).trim();
            if (name.isEmpty() || value.isEmpty()) {
                throw ApiException.badRequest("INVALID_SPEC", "Specifications: write each line as \u201CName: Value\u201D (\u201C" + trimTo(line, 40) + "\u201D)");
            }
            if (name.length() > 40 || value.length() > 160) {
                throw ApiException.badRequest("SPEC_TOO_LONG", "Specifications: keep names under 40 and values under 160 characters");
            }
            out.add(name + ": " + value);
        }
        if (out.size() > MAX_SPECS) {
            throw ApiException.badRequest("TOO_MANY_LINES", "Specifications: at most " + MAX_SPECS + " lines");
        }
        return out.isEmpty() ? null : String.join("\n", out);
    }

    static String lines(String raw, String label) {
        List<String> items = raw.lines().map(String::trim).map(s -> s.replaceFirst("^[-*•]\\s*", ""))
                .filter(s -> !s.isEmpty()).toList();
        if (items.size() > MAX_LIST_ITEMS) {
            throw ApiException.badRequest("TOO_MANY_LINES", label + ": at most " + MAX_LIST_ITEMS + " lines");
        }
        if (items.stream().anyMatch(s -> s.length() > MAX_ITEM_LENGTH)) {
            throw ApiException.badRequest("LINE_TOO_LONG", label + ": keep each line under " + MAX_ITEM_LENGTH + " characters");
        }
        return items.isEmpty() ? null : String.join("\n", items);
    }

    private SellingMethod requireAllowedMethod(Storefront sf, SellingMethod method, ProductType type) {
        if (method == SellingMethod.DROPSHIP && type == ProductType.DIGITAL) {
            throw ApiException.rule("DROPSHIP_PHYSICAL_ONLY", "Dropshipping is only for physical products");
        }
        if (method.onSite() && settings.affiliateOnly(sf)) {
            throw ApiException.rule("AFFILIATE_ONLY",
                    "Checkout is off for this store. Turn on \"Sell on this site\" in Settings to add own-stock or dropship products.");
        }
        return method;
    }

    private void syncInventory(Product p) {
        if (p.isOwnStock()) {
            p.getVariants().stream().filter(v -> v.getId() != null)
                    .forEach(v -> inventory.ensureItem(v.getId(), p.getStorefront()));
        }
    }

    private List<String> problems(Product p) {
        boolean hasLink = p.getSellingMethod() == SellingMethod.AFFILIATE && p.getId() != null && affiliate.hasActiveLink(p.getId());
        boolean ready = p.getProductType() == ProductType.DIGITAL && p.isOnSite() && delivery.isReady(p.getId(), p.getDeliveryKind());
        return ProductRules.publishProblems(p, new ProductRules.Context(hasLink, ready));
    }

    private AdminProductView view(Product p) {
        Map<Long, Integer> stock = p.isOwnStock()
                ? inventory.availability(p.getVariants().stream().map(ProductVariant::getId).filter(Objects::nonNull).toList())
                : Map.of();
        List<AdminVariantView> vs = p.getVariants().stream().map(v -> new AdminVariantView(v.getId(), v.getName(), v.getSku(),
                v.getPriceMinor(), v.getCompareAtMinor(), v.getWeightGrams(), v.isActive(), v.isDefaultVariant(),
                p.isOwnStock() ? stock.get(v.getId()) : null)).toList();
        List<AdminImageView> imgs = p.getImages().stream().map(i -> new AdminImageView(i.getId(), i.url(), i.thumbUrl(), i.getAltText())).toList();
        List<AdminVideoView> vids = p.getVideos().stream().map(v -> new AdminVideoView(v.getId(), v.url(), v.posterUrl(),
                v.getTitle(), v.getDurationSeconds(), v.getWidth(), v.getHeight(), v.getSizeBytes())).toList();
        List<AffiliateService.LinkView> links = p.getSellingMethod() == SellingMethod.AFFILIATE && p.getId() != null
                ? affiliate.adminLinks(p.getStorefront(), p.getId()) : List.of();
        AffiliateService.LinkView link = links.stream().filter(AffiliateService.LinkView::active).findFirst()
                .orElse(links.isEmpty() ? null : links.get(0));
        return new AdminProductView(p.getId(), p.getStorefront().name(), p.getProductType().name(), p.getSellingMethod().name(),
                p.getStatus().name(), p.getTitle(), p.getSlug(), p.getSummary(), p.getDescription(), p.getBrand(),
                p.getCategoryId(), p.getDigitalFormat(), p.getDeliveryKind() == null ? null : p.getDeliveryKind().name(),
                p.isTypeLocked(), p.getPublishedAt(), p.getUpdatedAt(), p.getVersion(), vs, imgs, vids, link, links, problems(p),
                p.getMetaTitle(), p.getMetaDescription(), p.getFocusKeyword(), p.isNoindex(), p.getHsnSac(), p.getGstRateBps(),
                p.getHighlights(), p.getPros(), p.getCons(), p.getVerdict(), p.getExpertRating(), p.getSpecs());
    }

    private static AdminCategoryView view(Category c) {
        return new AdminCategoryView(c.getId(), c.getName(), c.getSlug(), c.getSortOrder(), c.isActive(),
                c.getDescription(), c.getMetaTitle(), c.getMetaDescription());
    }

    private String uniqueSlug(Storefront sf, String source, Long currentId) {
        String base = slugify(source);
        String slug = base;
        int n = 2;
        while (true) {
            final String candidate = slug;
            boolean taken = products.existsByStorefrontAndSlug(sf, candidate)
                    && (currentId == null || products.findByIdAndStorefront(currentId, sf).map(p -> !p.getSlug().equals(candidate)).orElse(true));
            if (!taken) return slug;
            slug = base + "-" + n++;
        }
    }

    public static String slugify(String value) {
        String s = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (s.isEmpty()) s = "item";
        return s.length() > 140 ? s.substring(0, 140).replaceAll("-+$", "") : s;
    }

    private String requireUniqueSku(String sku, Long variantId) {
        if (sku == null || sku.isBlank()) return null;
        String s = sku.trim();
        if (s.length() > 80 || !s.matches("[A-Za-z0-9._\\-/]+")) {
            throw ApiException.rule("INVALID_SKU", "SKU may contain letters, numbers and . _ - / (max 80)");
        }
        boolean taken = variantId == null ? variants.existsBySkuIgnoreCase(s) : variants.existsBySkuIgnoreCaseAndIdNot(s, variantId);
        if (taken) throw ApiException.conflict("SKU_IN_USE", "SKU '" + s + "' is already used");
        return s;
    }

    private static Long validPrice(Long minor) {
        if (minor == null) return null;
        if (minor < 0 || minor > 100_000_000_00L) throw ApiException.rule("INVALID_PRICE", "Price is out of range");
        return minor;
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) throw ApiException.badRequest("FIELD_REQUIRED", label + " is required");
        if (value.length() > 200) throw ApiException.badRequest("FIELD_TOO_LONG", label + " is too long");
    }

    private static String trimTo(String v, int max) {
        String t = v.trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }
}
