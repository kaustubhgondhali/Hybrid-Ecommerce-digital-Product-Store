package com.hybrid.platform.offers;

import com.hybrid.platform.affiliate.AffiliateMerchant;
import com.hybrid.platform.affiliate.AffiliateService;
import com.hybrid.platform.affiliate.AffiliateUrlPolicy;
import com.hybrid.platform.catalog.Product;
import com.hybrid.platform.catalog.ProductRepository;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.audit.AuditService;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.security.AdminPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.*;

/**
 * Coupon codes and deals at partner stores (GrabOn / CouponDunia style). Shoppers copy the code and go to the store
 * through /go/o/{id}, which re-checks the destination against the approved store and counts the click.
 */
@Service
public class OfferService {

    public record OfferInput(String url, String title, String description, String code, Long productId, Instant startsAt,
                             Instant endsAt, Boolean active, Boolean verified, Boolean confirmNewDomain, String merchantName) {
    }

    public record AdminOfferView(Long id, String title, String description, String code, String url, String merchantName,
                                 String merchantDomain, Long productId, String productTitle, Instant startsAt, Instant endsAt,
                                 boolean active, boolean live, Instant verifiedAt, long clicks, Instant createdAt) {
    }

    /** What shoppers see; {@code goUrl} goes through the tracked redirect. */
    public record PublicOffer(Long id, String title, String description, String code, String merchantName,
                              String merchantDomain, Long productId, Instant endsAt, Instant verifiedAt, String goUrl) {
    }

    private final StoreOfferRepository offers;
    private final AffiliateService affiliate;
    private final ProductRepository products;
    private final AuditService audit;

    public OfferService(StoreOfferRepository offers, AffiliateService affiliate, ProductRepository products, AuditService audit) {
        this.offers = offers;
        this.affiliate = affiliate;
        this.products = products;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ admin

    @Transactional(readOnly = true)
    public List<AdminOfferView> list(Storefront sf) {
        List<StoreOffer> all = offers.findByStorefrontOrderByCreatedAtDesc(sf);
        Map<Long, String> titles = productTitles(all);
        Instant now = Instant.now();
        return all.stream().map(o -> adminView(o, titles, now)).toList();
    }

    @Transactional
    public AdminOfferView create(AdminPrincipal admin, OfferInput in) {
        StoreOffer o = new StoreOffer(admin.storefront());
        apply(admin, o, in, true);
        offers.save(o);
        audit.record(admin, "OFFER_CREATED", "StoreOffer", o.getId(), o.getMerchant().getDomain() + " " + o.getTitle());
        return adminView(o, productTitles(List.of(o)), Instant.now());
    }

    @Transactional
    public AdminOfferView update(AdminPrincipal admin, Long id, OfferInput in) {
        StoreOffer o = offers.findByIdAndStorefront(id, admin.storefront())
                .orElseThrow(() -> ApiException.notFound("OFFER_NOT_FOUND", "Offer not found"));
        apply(admin, o, in, false);
        audit.record(admin, "OFFER_UPDATED", "StoreOffer", id, null);
        return adminView(o, productTitles(List.of(o)), Instant.now());
    }

    @Transactional
    public void delete(AdminPrincipal admin, Long id) {
        StoreOffer o = offers.findByIdAndStorefront(id, admin.storefront())
                .orElseThrow(() -> ApiException.notFound("OFFER_NOT_FOUND", "Offer not found"));
        offers.delete(o);
        audit.record(admin, "OFFER_DELETED", "StoreOffer", id, o.getTitle());
    }

    private void apply(AdminPrincipal admin, StoreOffer o, OfferInput in, boolean creating) {
        if (in == null) throw ApiException.badRequest("OFFER_REQUIRED", "Offer details are required");
        if (creating || in.url() != null) {
            String raw = in.url() == null ? "" : in.url().strip();
            if (raw.isEmpty() || raw.length() > 2048) throw ApiException.badRequest("URL_REQUIRED", "Paste the affiliate link for this offer");
            URI uri = AffiliateUrlPolicy.parse(raw);
            AffiliateMerchant merchant = affiliate.approvedMerchant(admin, uri, Boolean.TRUE.equals(in.confirmNewDomain()), in.merchantName());
            o.setMerchant(merchant);
            o.setUrl(raw);
        }
        if (creating || in.title() != null) o.setTitle(text(in.title(), 140, "Title", true));
        if (in.description() != null) o.setDescription(text(in.description(), 500, "Description", false));
        if (in.code() != null) {
            String code = in.code().strip().toUpperCase(Locale.ROOT);
            if (!code.isEmpty() && !code.matches("[A-Z0-9._\\-]{2,40}")) {
                throw ApiException.badRequest("INVALID_CODE", "Coupon codes use letters, numbers, dot, dash or underscore (2-40)");
            }
            o.setCode(code.isEmpty() ? null : code);
        }
        if (in.productId() != null) {
            if (in.productId() <= 0) {
                o.setProductId(null);
            } else {
                Product p = products.findByIdAndStorefront(in.productId(), admin.storefront())
                        .orElseThrow(() -> ApiException.notFound("PRODUCT_NOT_FOUND", "Product not found"));
                o.setProductId(p.getId());
            }
        }
        if (in.startsAt() != null || creating) o.setStartsAt(in.startsAt());
        if (in.endsAt() != null || creating) o.setEndsAt(in.endsAt());
        if (o.getStartsAt() != null && o.getEndsAt() != null && !o.getEndsAt().isAfter(o.getStartsAt())) {
            throw ApiException.badRequest("INVALID_DATES", "The offer must end after it starts");
        }
        if (in.active() != null) o.setActive(in.active());
        if (Boolean.TRUE.equals(in.verified())) o.setVerifiedAt(Instant.now());
    }

    // ------------------------------------------------------------------ storefront

    /**
     * Live offers, newest first. With {@code store} only that store's offers; with {@code productId} the offers for that
     * product plus general offers at the stores that sell it.
     */
    @Transactional(readOnly = true)
    public List<PublicOffer> live(Storefront sf, String store, Long productId) {
        Instant now = Instant.now();
        Set<String> productStores = productId == null ? Set.of() : storesOf(productId);
        return offers.findByStorefrontAndActiveTrueOrderByCreatedAtDesc(sf).stream()
                .filter(o -> o.isLive(now))
                .filter(o -> store == null || store.isBlank() || store.equalsIgnoreCase(o.getMerchant().getDomain()))
                .filter(o -> productId == null || productId.equals(o.getProductId())
                        || (o.getProductId() == null && productStores.contains(o.getMerchant().getDomain())))
                .map(OfferService::publicView)
                .toList();
    }

    /** Resolves /go/o/{id}: live offer, destination still on the approved store; counts the click unless it is a bot. */
    @Transactional
    public Optional<String> redirect(Long id, String userAgent) {
        Optional<StoreOffer> found = offers.findById(id);
        if (found.isEmpty() || !found.get().isLive(Instant.now())) return Optional.empty();
        StoreOffer o = found.get();
        AffiliateMerchant m = o.getMerchant();
        try {
            URI uri = AffiliateUrlPolicy.parse(o.getUrl());
            AffiliateUrlPolicy.requireAllowedScheme(uri, m.isAllowHttp());
            if (!m.matchesHost(AffiliateUrlPolicy.hostKey(uri)) && !m.matchesHost(AffiliateUrlPolicy.domainKey(uri))) return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (!AffiliateService.isBot(userAgent)) offers.countClick(id);
        return Optional.of(AffiliateUrlPolicy.withSubId(o.getUrl(), m.getSubIdParam(), "offer" + id));
    }

    private Set<String> storesOf(Long productId) {
        Set<String> out = new HashSet<>();
        affiliate.offerLists(List.of(productId)).getOrDefault(productId, List.of()).forEach(v -> out.add(v.merchantDomain()));
        return out;
    }

    private Map<Long, String> productTitles(List<StoreOffer> list) {
        Map<Long, String> out = new HashMap<>();
        List<Long> ids = list.stream().map(StoreOffer::getProductId).filter(Objects::nonNull).distinct().toList();
        if (!ids.isEmpty()) products.findAllById(ids).forEach(p -> out.put(p.getId(), p.getTitle()));
        return out;
    }

    private static AdminOfferView adminView(StoreOffer o, Map<Long, String> titles, Instant now) {
        return new AdminOfferView(o.getId(), o.getTitle(), o.getDescription(), o.getCode(), o.getUrl(), o.getMerchant().getName(),
                o.getMerchant().getDomain(), o.getProductId(), o.getProductId() == null ? null : titles.get(o.getProductId()),
                o.getStartsAt(), o.getEndsAt(), o.isActive(), o.isLive(now), o.getVerifiedAt(), o.getClicks(), o.getCreatedAt());
    }

    static PublicOffer publicView(StoreOffer o) {
        return new PublicOffer(o.getId(), o.getTitle(), o.getDescription(), o.getCode(), o.getMerchant().getName(),
                o.getMerchant().getDomain(), o.getProductId(), o.getEndsAt(), o.getVerifiedAt(), "/go/o/" + o.getId());
    }

    private static String text(String v, int max, String label, boolean required) {
        String t = v == null ? "" : v.strip();
        if (t.isEmpty()) {
            if (required) throw ApiException.badRequest("FIELD_REQUIRED", label + " is required");
            return null;
        }
        if (t.length() > max) throw ApiException.badRequest("FIELD_TOO_LONG", label + " must be at most " + max + " characters");
        return t;
    }
}
