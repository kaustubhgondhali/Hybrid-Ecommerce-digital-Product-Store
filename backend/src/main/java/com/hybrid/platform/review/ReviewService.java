package com.hybrid.platform.review;

import com.hybrid.platform.catalog.CatalogEnums.ProductStatus;
import com.hybrid.platform.catalog.CatalogEnums.SellingMethod;
import com.hybrid.platform.catalog.Product;
import com.hybrid.platform.catalog.ProductRepository;
import com.hybrid.platform.commerce.order.CustomerOrder;
import com.hybrid.platform.commerce.order.OrderRepositories.OrderRepository;
import com.hybrid.platform.commerce.settings.SettingsService;
import com.hybrid.platform.common.PageResponse;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.audit.AuditService;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.security.AdminPrincipal;
import com.hybrid.platform.security.CustomerPrincipal;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Product reviews. Products we sell directly can only be reviewed by customers who bought them (verified purchase);
 * partner (affiliate) products can be reviewed by any signed-in customer and are never marked verified.
 */
@Service
public class ReviewService {

    private static final Set<CustomerOrder.Status> PURCHASED = EnumSet.of(CustomerOrder.Status.PAID,
            CustomerOrder.Status.SHIPPED, CustomerOrder.Status.DELIVERED, CustomerOrder.Status.COMPLETED);

    public record Rating(double average, long count) {
        public static final Rating NONE = new Rating(0, 0);
    }

    public record PublicReview(Long id, String authorName, int rating, String title, String body,
                               boolean verifiedPurchase, String adminReply, Instant createdAt) {
    }

    public record ProductReviews(Rating rating, PageResponse<PublicReview> reviews, boolean canReview,
                                 String cannotReviewReason, MyReview mine) {
    }

    public record MyReview(int rating, String title, String body, String status) {
    }

    public record ReviewInput(Integer rating, String title, String body) {
    }

    public record AdminReview(Long id, Long productId, String productTitle, String productSlug, String authorName,
                              int rating, String title, String body, String status, boolean verifiedPurchase,
                              String adminReply, Instant createdAt) {
    }

    public record ModerationInput(String status, String adminReply) {
    }

    private final ProductReviewRepository reviews;
    private final ProductRepository products;
    private final OrderRepository orders;
    private final SettingsService settings;
    private final AuditService audit;

    public ReviewService(ProductReviewRepository reviews, ProductRepository products, OrderRepository orders,
                         SettingsService settings, AuditService audit) {
        this.reviews = reviews;
        this.products = products;
        this.orders = orders;
        this.settings = settings;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Rating rating(Long productId) {
        return ratings(List.of(productId)).getOrDefault(productId, Rating.NONE);
    }

    @Transactional(readOnly = true)
    public Map<Long, Rating> ratings(Collection<Long> productIds) {
        if (productIds.isEmpty()) return Map.of();
        Map<Long, Rating> out = new HashMap<>();
        for (var row : reviews.ratings(productIds, ProductReview.Status.APPROVED)) {
            out.put(row.getProductId(), new Rating(Math.round(row.getAverage() * 10) / 10.0, row.getCount()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<PublicReview> latestApproved(Long productId, int max) {
        return reviews.findByProductIdAndStatusOrderByCreatedAtDesc(productId, ProductReview.Status.APPROVED,
                PageRequest.of(0, max)).map(ReviewService::publicView).getContent();
    }

    @Transactional(readOnly = true)
    public ProductReviews forProduct(Storefront sf, String slug, int page, Optional<CustomerPrincipal> customer) {
        Product p = published(sf, slug);
        var result = reviews.findByProductIdAndStatusOrderByCreatedAtDesc(p.getId(), ProductReview.Status.APPROVED,
                PageRequest.of(Math.max(page, 0), 10));
        boolean canReview = false;
        String reason = "Sign in to write a review.";
        MyReview mine = null;
        if (customer.isPresent()) {
            Long cid = customer.get().id();
            mine = reviews.findByProductIdAndCustomerId(p.getId(), cid)
                    .map(r -> new MyReview(r.getRating(), r.getTitle(), r.getBody(), r.getStatus().name())).orElse(null);
            if (p.getSellingMethod() == SellingMethod.AFFILIATE || orders.hasPurchased(cid, p.getId(), PURCHASED)) {
                canReview = true;
                reason = null;
            } else {
                reason = "Only customers who bought this product can review it.";
            }
        }
        return new ProductReviews(rating(p.getId()), PageResponse.of(result, ReviewService::publicView), canReview, reason, mine);
    }

    @Transactional
    public MyReview submit(CustomerPrincipal customer, Storefront sf, String slug, ReviewInput in) {
        Product p = published(sf, slug);
        boolean verified = orders.hasPurchased(customer.id(), p.getId(), PURCHASED);
        if (p.getSellingMethod() == SellingMethod.DIRECT && !verified) {
            throw ApiException.forbidden("REVIEW_NOT_ALLOWED", "Only customers who bought this product can review it");
        }
        if (in.rating() == null || in.rating() < 1 || in.rating() > 5) {
            throw ApiException.badRequest("INVALID_RATING", "Choose a rating from 1 to 5 stars");
        }
        String title = clean(in.title(), 120, "Title");
        String body = clean(in.body(), 3000, "Review");
        ProductReview r = reviews.findByProductIdAndCustomerId(p.getId(), customer.id())
                .orElseGet(() -> new ProductReview(sf, p.getId(), customer.id()));
        r.setAuthorName(displayName(customer.fullName()));
        r.setRating(in.rating());
        r.setTitle(title);
        r.setBody(body);
        r.setVerifiedPurchase(verified && p.getSellingMethod() == SellingMethod.DIRECT);
        r.setStatus(settings.entity(sf).isReviewsAutoApprove() ? ProductReview.Status.APPROVED : ProductReview.Status.PENDING);
        reviews.save(r);
        return new MyReview(r.getRating(), r.getTitle(), r.getBody(), r.getStatus().name());
    }

    // ------------------------------------------------------------------ admin

    @Transactional(readOnly = true)
    public PageResponse<AdminReview> adminList(Storefront sf, String status, int page) {
        var pageable = PageRequest.of(Math.max(page, 0), 25);
        var result = status == null || status.isBlank()
                ? reviews.findByStorefrontOrderByCreatedAtDesc(sf, pageable)
                : reviews.findByStorefrontAndStatusOrderByCreatedAtDesc(sf, parseStatus(status), pageable);
        Map<Long, Product> byId = new HashMap<>();
        products.findAllById(result.getContent().stream().map(ProductReview::getProductId).distinct().toList())
                .forEach(p -> byId.put(p.getId(), p));
        return PageResponse.of(result, r -> {
            Product p = byId.get(r.getProductId());
            return new AdminReview(r.getId(), r.getProductId(), p == null ? "(deleted)" : p.getTitle(),
                    p == null ? null : p.getSlug(), r.getAuthorName(), r.getRating(), r.getTitle(), r.getBody(),
                    r.getStatus().name(), r.isVerifiedPurchase(), r.getAdminReply(), r.getCreatedAt());
        });
    }

    @Transactional(readOnly = true)
    public long pendingCount(Storefront sf) {
        return reviews.countByStorefrontAndStatus(sf, ProductReview.Status.PENDING);
    }

    @Transactional
    public void moderate(AdminPrincipal admin, Long id, ModerationInput in) {
        ProductReview r = reviews.findByIdAndStorefront(id, admin.storefront())
                .orElseThrow(() -> ApiException.notFound("REVIEW_NOT_FOUND", "Review not found"));
        if (in.status() != null) r.setStatus(parseStatus(in.status()));
        if (in.adminReply() != null) r.setAdminReply(clean(in.adminReply(), 2000, "Reply"));
        audit.record(admin, "REVIEW_MODERATED", "ProductReview", id, r.getStatus().name());
    }

    @Transactional
    public void delete(AdminPrincipal admin, Long id) {
        ProductReview r = reviews.findByIdAndStorefront(id, admin.storefront())
                .orElseThrow(() -> ApiException.notFound("REVIEW_NOT_FOUND", "Review not found"));
        reviews.delete(r);
        audit.record(admin, "REVIEW_DELETED", "ProductReview", id, null);
    }

    private Product published(Storefront sf, String slug) {
        return products.findByStorefrontAndSlugAndStatus(sf, slug, ProductStatus.PUBLISHED)
                .orElseThrow(() -> ApiException.notFound("PRODUCT_NOT_FOUND", "Product not found"));
    }

    private static ProductReview.Status parseStatus(String s) {
        try {
            return ProductReview.Status.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_STATUS", "Status must be PENDING, APPROVED or REJECTED");
        }
    }

    /** Public reviews show first name and last initial only. */
    static String displayName(String fullName) {
        if (fullName == null || fullName.isBlank()) return "Customer";
        String[] parts = fullName.trim().split("\\s+");
        String first = parts[0].length() > 40 ? parts[0].substring(0, 40) : parts[0];
        return parts.length > 1 ? first + " " + parts[parts.length - 1].charAt(0) + "." : first;
    }

    private static String clean(String v, int max, String label) {
        if (v == null) return null;
        String t = v.strip();
        if (t.length() > max) throw ApiException.badRequest("FIELD_TOO_LONG", label + " must be at most " + max + " characters");
        return t.isEmpty() ? null : t;
    }

    private static PublicReview publicView(ProductReview r) {
        return new PublicReview(r.getId(), r.getAuthorName(), r.getRating(), r.getTitle(), r.getBody(),
                r.isVerifiedPurchase(), r.getAdminReply(), r.getCreatedAt());
    }
}
