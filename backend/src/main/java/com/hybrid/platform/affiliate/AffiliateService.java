package com.hybrid.platform.affiliate;

import com.hybrid.platform.affiliate.AffiliateRepositories.ClickRepository;
import com.hybrid.platform.affiliate.AffiliateRepositories.ConversionRepository;
import com.hybrid.platform.affiliate.AffiliateRepositories.LinkRepository;
import com.hybrid.platform.affiliate.AffiliateRepositories.MerchantRepository;
import com.hybrid.platform.affiliate.AffiliateRepositories.PricePointRepository;
import com.hybrid.platform.catalog.CatalogEnums.ProductStatus;
import com.hybrid.platform.catalog.CatalogEnums.SellingMethod;
import com.hybrid.platform.catalog.CatalogQueryService;
import com.hybrid.platform.common.PageResponse;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.audit.AuditService;
import com.hybrid.platform.common.crypto.CryptoService;
import com.hybrid.platform.common.crypto.Hashing;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.security.AdminPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class AffiliateService {

    public static final String DEFAULT_DISCLOSURE = "We may earn a commission when you buy through this link, at no extra cost to you.";
    public static final int PRICE_DROP_MIN_PERCENT = 5;

    /**
     * One partner offer as shoppers see it. {@code displayPriceMinor} (and {@code listPriceMinor}) are null when no price
     * was entered, the price is older than the allowed age, or the merchant's prices must not be shown
     * ({@code priceHidden}: shoppers see "Check price on ..."). {@code offerCount} is how many stores offer the product.
     */
    public record OfferView(Long linkId, String merchantName, String merchantDomain, Long displayPriceMinor,
                            Long listPriceMinor, Instant priceCheckedAt, boolean priceStale, boolean priceHidden,
                            String disclosure, String goUrl, int offerCount) {

        /** Percentage off the original price, when both prices are shown and the offer is actually cheaper. */
        public Integer discountPercent() {
            return discount(displayPriceMinor, listPriceMinor);
        }
    }

    public record LinkView(Long id, Long productId, String productTitle, String code, String goUrl, String destinationUrl,
                           Long merchantId, String merchantName, String merchantDomain, Long displayPriceMinor,
                           Long listPriceMinor, Instant priceCheckedAt, boolean priceStale, boolean merchantHidesPrices,
                           String disclosure, boolean active, long clicks30d,
                           String healthStatus, Instant healthCheckedAt, String healthDetail) {
    }

    public record MerchantView(Long id, String name, String domain, String subIdParam, boolean allowHttp, boolean active,
                               boolean hidePrices, Instant createdAt) {
    }

    public record SetLinkCommand(String url, Long displayPriceMinor, Long listPriceMinor, String disclosure,
                                 String merchantName, boolean confirmNewDomain) {
    }

    /** Recorded price movement for a product over the last 30 days, based on prices shoppers could see. */
    public record PriceInsight(Long low30Minor, Long high30Minor, boolean lowest30d, Integer dropPercent7d) {
    }

    public record PriceDrop(Long productId, long oldPriceMinor, long newPriceMinor, int percent, Instant at) {
    }

    /** Best offer first: lowest shown price, then offers without a shown price, oldest link first. */
    static final Comparator<OfferView> OFFER_ORDER = Comparator
            .comparing((OfferView o) -> o.displayPriceMinor() == null ? Long.MAX_VALUE : o.displayPriceMinor())
            .thenComparing(OfferView::linkId);

    public static Integer discount(Long price, Long listPrice) {
        if (price == null || listPrice == null || listPrice <= 0 || price >= listPrice) return null;
        int pct = (int) ((listPrice - price) * 100 / listPrice);
        return pct >= 1 ? pct : null;
    }

    public record ConversionView(Long id, String externalTxnId, String clickRef, Long merchantId, Long saleAmountMinor,
                                 Long commissionMinor, String status, String source, String note, Instant reportedAt) {
    }

    public record ImportResult(int created, int updated, int matchedClicks, List<String> errors) {
    }

    /** Click and conversion performance over the last 30 days. {@code conversionRatePct} is null without clicks. */
    public record PerformanceRow(Long productId, String label, String merchantName, long clicks30d, long conversions30d,
                                 long commission30dMinor, Double conversionRatePct, Long commissionPerClickMinor) {
    }

    public record Report(long clicks7d, long clicks30d, Map<String, Long> conversionsByStatus,
                         Map<String, Long> commissionByStatusMinor, List<LinkView> topLinks,
                         long stalePrices, List<PerformanceRow> products, List<PerformanceRow> merchants) {
    }

    public record ClickContext(Long customerId, String ip, String userAgent, String referer) {
    }

    private final MerchantRepository merchants;
    private final LinkRepository links;
    private final ClickRepository clicks;
    private final ConversionRepository conversions;
    private final PricePointRepository pricePoints;
    private final CatalogQueryService catalog;
    private final AuditService audit;
    private final CryptoService crypto;
    private final ApplicationEventPublisher publisher;
    private final Duration priceMaxAge;

    public AffiliateService(MerchantRepository merchants, LinkRepository links, ClickRepository clicks,
                            ConversionRepository conversions, PricePointRepository pricePoints, CatalogQueryService catalog,
                            AuditService audit, CryptoService crypto, ApplicationEventPublisher publisher,
                            @Value("${app.affiliate.price-max-age-days:7}") int priceMaxAgeDays) {
        this.publisher = publisher;
        this.merchants = merchants;
        this.links = links;
        this.clicks = clicks;
        this.conversions = conversions;
        this.pricePoints = pricePoints;
        this.catalog = catalog;
        this.audit = audit;
        this.crypto = crypto;
        this.priceMaxAge = Duration.ofDays(Math.max(1, priceMaxAgeDays));
    }

    // ------------------------------------------------------------------ storefront queries

    /** The best offer per product (see {@link #OFFER_ORDER}); products without a live offer are absent. */
    @Transactional(readOnly = true)
    public Map<Long, OfferView> offers(Collection<Long> productIds) {
        Map<Long, OfferView> best = new HashMap<>();
        offerLists(productIds).forEach((id, list) -> best.put(id, list.get(0)));
        return best;
    }

    /** Every live offer per product, best first. */
    @Transactional(readOnly = true)
    public Map<Long, List<OfferView>> offerLists(Collection<Long> productIds) {
        Map<Long, List<OfferView>> result = new HashMap<>();
        if (productIds.isEmpty()) return result;
        Instant now = Instant.now();
        Map<Long, List<AffiliateLink>> live = new HashMap<>();
        for (AffiliateLink l : links.findByProductIdIn(productIds)) {
            if (isLive(l)) live.computeIfAbsent(l.getProductId(), k -> new ArrayList<>()).add(l);
        }
        live.forEach((productId, list) -> result.put(productId, list.stream()
                .map(l -> offerView(l, now, list.size())).sorted(OFFER_ORDER).toList()));
        return result;
    }

    private OfferView offerView(AffiliateLink l, Instant now, int count) {
        AffiliateMerchant m = l.getMerchant();
        boolean stale = isStale(l, now);
        boolean show = !stale && !m.isHidePrices();
        return new OfferView(l.getId(), m.getName(), m.getDomain(), show ? l.getDisplayPriceMinor() : null,
                show && l.getDisplayPriceMinor() != null ? l.getListPriceMinor() : null, l.getPriceCheckedAt(), stale,
                m.isHidePrices(), l.getDisclosure(), "/go/" + l.getCode(), count);
    }

    /** Shown to shoppers: active, store active, and not found dead by the nightly link check. */
    private static boolean isLive(AffiliateLink l) {
        return l.isActive() && l.getMerchant().isActive() && !l.isBroken();
    }

    /**
     * Lowest/highest shown price in the last 30 days and whether today's best price is the lowest of them (only
     * claimed when the price really was higher at some point), plus the latest drop of at least
     * {@link #PRICE_DROP_MIN_PERCENT}% in the last 7 days.
     */
    @Transactional(readOnly = true)
    public Map<Long, PriceInsight> insights(Map<Long, OfferView> best) {
        Map<Long, PriceInsight> out = new HashMap<>();
        List<Long> priced = best.entrySet().stream().filter(e -> e.getValue().displayPriceMinor() != null).map(Map.Entry::getKey).toList();
        if (priced.isEmpty()) return out;
        Instant now = Instant.now();
        Instant since30 = now.minus(Duration.ofDays(30));
        Instant since7 = now.minus(Duration.ofDays(7));
        Map<Long, List<AffiliatePricePoint>> byProduct = new HashMap<>();
        for (AffiliatePricePoint p : pricePoints.findByProductIdInAndRecordedAtAfterOrderByRecordedAtAsc(priced, now.minus(Duration.ofDays(60)))) {
            byProduct.computeIfAbsent(p.getProductId(), k -> new ArrayList<>()).add(p);
        }
        for (Long id : priced) {
            OfferView offer = best.get(id);
            long current = offer.displayPriceMinor();
            List<AffiliatePricePoint> points = byProduct.getOrDefault(id, List.of());
            long low = current, high = current;
            for (AffiliatePricePoint p : points) {
                if (p.getRecordedAt().isBefore(since30)) continue;
                low = Math.min(low, p.getPriceMinor());
                high = Math.max(high, p.getPriceMinor());
            }
            boolean lowest = current <= low && high > current;
            Integer drop = null;
            List<AffiliatePricePoint> own = points.stream().filter(p -> p.getLinkId().equals(offer.linkId())).toList();
            for (int i = own.size() - 1; i > 0; i--) {
                AffiliatePricePoint cur = own.get(i);
                if (cur.getRecordedAt().isBefore(since7)) break;
                long before = own.get(i - 1).getPriceMinor();
                if (cur.getPriceMinor() == current && isPriceDrop(before, cur.getPriceMinor())) {
                    drop = (int) ((before - current) * 100 / before);
                    break;
                }
            }
            out.put(id, new PriceInsight(low, high, lowest, drop));
        }
        return out;
    }

    /** Price cuts of at least {@link #PRICE_DROP_MIN_PERCENT}% recorded since {@code since}; the latest per product. */
    @Transactional(readOnly = true)
    public List<PriceDrop> recentPriceDrops(Storefront sf, Instant since) {
        Map<Long, PriceDrop> latest = new HashMap<>();
        List<AffiliatePricePoint> history = pricePoints.historyOfLinksChangedSince(sf, since);
        for (int i = 1; i < history.size(); i++) {
            AffiliatePricePoint prev = history.get(i - 1);
            AffiliatePricePoint cur = history.get(i);
            if (!prev.getLinkId().equals(cur.getLinkId()) || cur.getRecordedAt().isBefore(since)) continue;
            if (isPriceDrop(prev.getPriceMinor(), cur.getPriceMinor())) {
                int pct = (int) ((prev.getPriceMinor() - cur.getPriceMinor()) * 100 / prev.getPriceMinor());
                PriceDrop d = new PriceDrop(cur.getProductId(), prev.getPriceMinor(), cur.getPriceMinor(), pct, cur.getRecordedAt());
                latest.merge(cur.getProductId(), d, (a, b) -> a.at().isAfter(b.at()) ? a : b);
            }
        }
        return latest.values().stream().sorted(Comparator.comparing(PriceDrop::at).reversed()).toList();
    }

    /** Product ids ranked by real partner clicks in the last {@code days} days. */
    @Transactional(readOnly = true)
    public List<Long> trendingProductIds(Storefront sf, int days, int limit) {
        return clicks.topProductsSince(sf, Instant.now().minus(Duration.ofDays(days)), PageRequest.of(0, limit)).stream()
                .map(r -> (Long) r[0]).toList();
    }

    /** Real (non-bot) clicks to partner stores for one product in the last {@code days} days. */
    @Transactional(readOnly = true)
    public long clicksSince(Long productId, int days) {
        return clicks.countForProductSince(productId, Instant.now().minus(Duration.ofDays(days)));
    }

    /** Real clicks per product in the last {@code days} days (products without clicks are absent). */
    @Transactional(readOnly = true)
    public Map<Long, Long> productClickCounts(Storefront sf, int days) {
        Map<Long, Long> out = new HashMap<>();
        for (Object[] r : clicks.topProductsSince(sf, Instant.now().minus(Duration.ofDays(days)), PageRequest.of(0, 5000))) {
            out.put((Long) r[0], (Long) r[1]);
        }
        return out;
    }

    /**
     * The approved store for an affiliate URL. A new domain is only approved when the admin confirms it (redirects can
     * then go to that website); disabled stores and plain http (unless allowed for the store) are refused.
     */
    @Transactional
    public AffiliateMerchant approvedMerchant(AdminPrincipal admin, URI uri, boolean confirmNewDomain, String merchantName) {
        Storefront sf = admin.storefront();
        String domain = AffiliateUrlPolicy.domainKey(uri);
        AffiliateMerchant merchant = merchants.findByStorefrontAndDomain(sf, domain).orElse(null);
        if (merchant == null) {
            if (!confirmNewDomain) {
                throw new ApiException(HttpStatus.CONFLICT, "AFFILIATE_DOMAIN_UNCONFIRMED",
                        "'" + domain + "' is a new affiliate domain. Confirm it to allow redirects to this website.",
                        Map.of("domain", domain));
            }
            String name = merchantName == null || merchantName.isBlank() ? domain : merchantName.trim();
            merchant = new AffiliateMerchant(sf, name, domain);
            merchant.setAllowHttp("http".equalsIgnoreCase(uri.getScheme()));
            merchant = merchants.save(merchant);
            audit.record(admin, "AFFILIATE_DOMAIN_APPROVED", "AffiliateMerchant", merchant.getId(), domain);
        } else if (!merchant.isActive()) {
            throw ApiException.rule("AFFILIATE_MERCHANT_DISABLED", "The merchant '" + merchant.getName() + "' is disabled");
        }
        AffiliateUrlPolicy.requireAllowedScheme(uri, merchant.isAllowHttp());
        return merchant;
    }

    public Duration priceMaxAge() {
        return priceMaxAge;
    }

    static boolean isPriceDrop(Long oldPrice, Long newPrice) {
        return oldPrice != null && newPrice != null && newPrice > 0
                && newPrice * 100 <= oldPrice * (100 - PRICE_DROP_MIN_PERCENT);
    }

    boolean isStale(AffiliateLink l, Instant now) {
        return l.getDisplayPriceMinor() != null
                && (l.getPriceCheckedAt() == null || l.getPriceCheckedAt().isBefore(now.minus(priceMaxAge)));
    }

    @Transactional(readOnly = true)
    public boolean hasActiveLink(Long productId) {
        return links.findByProductIdOrderByIdAsc(productId).stream().anyMatch(AffiliateService::isLive);
    }

    // ------------------------------------------------------------------ redirect

    /** Resolves /go/{code}: re-validates the stored destination against its merchant, records the click. */
    @Transactional
    public Optional<String> resolveRedirect(String code, ClickContext ctx) {
        if (code == null || !code.matches("[a-z0-9]{6,20}")) {
            return Optional.empty();
        }
        Optional<AffiliateLink> found = links.findByCode(code);
        if (found.isEmpty() || !found.get().isActive() || !found.get().getMerchant().isActive()) {
            return Optional.empty();
        }
        AffiliateLink link = found.get();
        var product = catalog.product(link.getProductId());
        if (product.isEmpty() || product.get().status() != ProductStatus.PUBLISHED
                || product.get().sellingMethod() != SellingMethod.AFFILIATE) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = AffiliateUrlPolicy.parse(link.getDestinationUrl());
            AffiliateUrlPolicy.requireAllowedScheme(uri, link.getMerchant().isAllowHttp());
        } catch (ApiException e) {
            return Optional.empty();
        }
        if (!link.getMerchant().matchesHost(AffiliateUrlPolicy.hostKey(uri))
                && !link.getMerchant().matchesHost(AffiliateUrlPolicy.domainKey(uri))) {
            return Optional.empty();
        }
        String clickRef = "c" + Hashing.randomCode(15);
        String ua = ctx.userAgent() == null ? "" : ctx.userAgent();
        clicks.save(new AffiliateClick(clickRef, link, ctx.customerId(), crypto.saltedHash(ctx.ip()),
                ua.isEmpty() ? null : Hashing.sha256Hex(ua), refererHost(ctx.referer()), isBot(ua)));
        return Optional.of(AffiliateUrlPolicy.withSubId(link.getDestinationUrl(), link.getMerchant().getSubIdParam(), clickRef));
    }

    // ------------------------------------------------------------------ admin: links

    /** All of a product's partner links (active and paused), oldest first. */
    @Transactional(readOnly = true)
    public List<LinkView> adminLinks(Storefront sf, Long productId) {
        Map<Long, Long> counts = clickCounts(sf, 30);
        return links.findByProductIdOrderByIdAsc(productId).stream().filter(l -> l.getStorefront() == sf)
                .map(l -> toView(l, null, counts)).toList();
    }

    /** Adds the link for the URL's store, or updates the product's existing link to that store. */
    @Transactional
    public LinkView setLink(AdminPrincipal admin, Long productId, SetLinkCommand cmd) {
        return saveLink(admin, productId, null, cmd);
    }

    /** Edits one specific link (its URL may move to another store the product is not linked to yet). */
    @Transactional
    public LinkView updateLink(AdminPrincipal admin, Long productId, Long linkId, SetLinkCommand cmd) {
        return saveLink(admin, productId, linkId, cmd);
    }

    /** Pauses or resumes one store's offer; paused offers are hidden from shoppers but keep their click history. */
    @Transactional
    public LinkView setLinkActive(AdminPrincipal admin, Long productId, Long linkId, boolean active) {
        AffiliateLink link = requireLink(admin.storefront(), productId, linkId);
        link.setActive(active);
        audit.record(admin, active ? "AFFILIATE_LINK_RESUMED" : "AFFILIATE_LINK_PAUSED", "Product", productId,
                "merchant=" + link.getMerchant().getDomain());
        return toView(link, null, Map.of());
    }

    private LinkView saveLink(AdminPrincipal admin, Long productId, Long linkId, SetLinkCommand cmd) {
        Storefront sf = admin.storefront();
        var product = catalog.requireProduct(sf, productId);
        if (product.sellingMethod() != SellingMethod.AFFILIATE) {
            throw ApiException.rule("NOT_AFFILIATE_PRODUCT", "Only AFFILIATE products can have an affiliate link");
        }
        URI uri = AffiliateUrlPolicy.parse(cmd.url());
        AffiliateMerchant merchant = approvedMerchant(admin, uri, cmd.confirmNewDomain(), cmd.merchantName());
        if ((cmd.displayPriceMinor() != null && cmd.displayPriceMinor() < 0) || (cmd.listPriceMinor() != null && cmd.listPriceMinor() < 0)) {
            throw ApiException.rule("INVALID_PRICE", "Prices cannot be negative");
        }

        List<AffiliateLink> existing = links.findByProductIdOrderByIdAsc(productId);
        final Long merchantId = merchant.getId();
        AffiliateLink sameStore = existing.stream().filter(l -> l.getMerchant().getId().equals(merchantId)).findFirst().orElse(null);
        AffiliateLink link;
        if (linkId != null) {
            link = existing.stream().filter(l -> l.getId().equals(linkId)).findFirst()
                    .orElseThrow(() -> ApiException.notFound("AFFILIATE_LINK_NOT_FOUND", "Link not found"));
            if (sameStore != null && !sameStore.getId().equals(link.getId())) {
                throw ApiException.conflict("MERCHANT_ALREADY_LINKED",
                        "This product already has a link to " + merchant.getName() + ". Edit that link instead.");
            }
        } else {
            link = sameStore != null ? sameStore : new AffiliateLink(sf, productId, newCode());
        }
        Long oldPrice = link.getDisplayPriceMinor();
        Long newPrice = cmd.displayPriceMinor();
        link.setMerchant(merchant);
        if (!cmd.url().trim().equals(link.getDestinationUrl())) link.resetHealth();
        link.setDestinationUrl(cmd.url().trim());
        if (!Objects.equals(oldPrice, newPrice)) {
            link.setPriceCheckedAt(newPrice == null ? null : Instant.now());
        }
        link.setDisplayPriceMinor(newPrice);
        link.setListPriceMinor(cmd.listPriceMinor() == null || cmd.listPriceMinor() == 0 ? null : cmd.listPriceMinor());
        link.setDisclosure(cmd.disclosure() == null || cmd.disclosure().isBlank() ? DEFAULT_DISCLOSURE : cmd.disclosure().trim());
        link.setActive(true);
        link = links.save(link);
        if (newPrice != null && !Objects.equals(oldPrice, newPrice)) {
            pricePoints.save(new AffiliatePricePoint(sf, productId, link.getId(), newPrice, Instant.now()));
        }
        catalog.lockTypes(List.of(productId));
        audit.record(admin, "AFFILIATE_LINK_SET", "Product", productId, "domain=" + merchant.getDomain());
        // Stores that forbid showing prices never get price-drop alerts (they would reveal the price).
        if (isPriceDrop(oldPrice, newPrice) && product.status() == ProductStatus.PUBLISHED && !merchant.isHidePrices()) {
            publisher.publishEvent(new AffiliateEvents.PriceDropped(sf, productId, product.title(), merchant.getName(),
                    oldPrice, newPrice));
        }
        return toView(link, product.title(), Map.of());
    }

    /** Marks a link's partner price as re-checked now, so it shows on the storefront again. */
    @Transactional
    public LinkView confirmPrice(AdminPrincipal admin, Long productId, Long linkId) {
        AffiliateLink link = requireLink(admin.storefront(), productId, linkId);
        if (link.getDisplayPriceMinor() == null) {
            throw ApiException.rule("NO_DISPLAY_PRICE", "Enter a display price first");
        }
        link.setPriceCheckedAt(Instant.now());
        audit.record(admin, "AFFILIATE_PRICE_CONFIRMED", "Product", productId, "price=" + link.getDisplayPriceMinor());
        return toView(link, null, Map.of());
    }

    /** Re-confirms every priced link of the product (the single-link endpoint kept for older clients). */
    @Transactional
    public LinkView confirmPrice(AdminPrincipal admin, Long productId) {
        Storefront sf = admin.storefront();
        catalog.requireProduct(sf, productId);
        List<AffiliateLink> priced = links.findByProductIdOrderByIdAsc(productId).stream()
                .filter(l -> l.getStorefront() == sf && l.getDisplayPriceMinor() != null).toList();
        if (priced.isEmpty()) {
            throw ApiException.rule("NO_DISPLAY_PRICE", "Enter a display price first");
        }
        priced.forEach(l -> l.setPriceCheckedAt(Instant.now()));
        audit.record(admin, "AFFILIATE_PRICE_CONFIRMED", "Product", productId, "links=" + priced.size());
        return toView(priced.get(0), null, Map.of());
    }

    private AffiliateLink requireLink(Storefront sf, Long productId, Long linkId) {
        catalog.requireProduct(sf, productId);
        return links.findById(linkId).filter(l -> l.getStorefront() == sf && l.getProductId().equals(productId))
                .orElseThrow(() -> ApiException.notFound("AFFILIATE_LINK_NOT_FOUND", "Link not found"));
    }

    @Transactional(readOnly = true)
    public List<LinkView> listLinks(Storefront sf) {
        Map<Long, Long> counts = clickCounts(sf, 30);
        List<AffiliateLink> all = links.findByStorefrontOrderByUpdatedAtDesc(sf);
        return all.stream().map(l -> toView(l, catalog.product(l.getProductId()).map(CatalogQueryService.ProductRef::title).orElse("(deleted)"), counts)).toList();
    }

    // ------------------------------------------------------------------ admin: merchants

    @Transactional(readOnly = true)
    public List<MerchantView> listMerchants(Storefront sf) {
        return merchants.findByStorefrontOrderByNameAsc(sf).stream().map(AffiliateService::toView).toList();
    }

    @Transactional
    public MerchantView updateMerchant(AdminPrincipal admin, Long id, String name, String subIdParam, Boolean allowHttp,
                                       Boolean active, Boolean hidePrices) {
        AffiliateMerchant m = merchants.findByIdAndStorefront(id, admin.storefront())
                .orElseThrow(() -> ApiException.notFound("MERCHANT_NOT_FOUND", "Merchant not found"));
        AffiliateUrlPolicy.requireValidParamName(subIdParam);
        if (name != null && !name.isBlank()) m.setName(name.trim());
        if (subIdParam != null) m.setSubIdParam(subIdParam);
        if (allowHttp != null) m.setAllowHttp(allowHttp);
        if (active != null) m.setActive(active);
        if (hidePrices != null) m.setHidePrices(hidePrices);
        audit.record(admin, "AFFILIATE_MERCHANT_UPDATED", "AffiliateMerchant", id,
                "subId=" + m.getSubIdParam() + " http=" + m.isAllowHttp() + " active=" + m.isActive() + " hidePrices=" + m.isHidePrices());
        return toView(m);
    }

    // ------------------------------------------------------------------ admin: conversions & reports

    @Transactional(readOnly = true)
    public PageResponse<ConversionView> listConversions(Storefront sf, int page, int size) {
        return PageResponse.of(conversions.findByStorefrontOrderByReportedAtDesc(sf,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100))), AffiliateService::toView);
    }

    @Transactional
    public ConversionView updateConversion(AdminPrincipal admin, Long id, AffiliateConversion.Status status, String note) {
        AffiliateConversion c = conversions.findByIdAndStorefront(id, admin.storefront())
                .orElseThrow(() -> ApiException.notFound("CONVERSION_NOT_FOUND", "Conversion not found"));
        if (status != null) c.setStatus(status);
        if (note != null) c.setNote(note.length() > 300 ? note.substring(0, 300) : note);
        audit.record(admin, "AFFILIATE_CONVERSION_UPDATED", "AffiliateConversion", id, "status=" + c.getStatus());
        return toView(c);
    }

    /**
     * CSV columns: external_txn_id, click_ref, sale_amount, commission, status
     * (amounts in rupees, e.g. 1499.00; status PENDING|APPROVED|REVERSED|PAID). A header row is optional.
     */
    @Transactional
    public ImportResult importConversions(AdminPrincipal admin, String csv) {
        Storefront sf = admin.storefront();
        int created = 0, updated = 0, matched = 0;
        List<String> errors = new ArrayList<>();
        String[] lines = csv == null ? new String[0] : csv.split("\\r?\\n");
        if (lines.length > 5000) {
            throw ApiException.rule("IMPORT_TOO_LARGE", "Import at most 5000 rows at a time");
        }
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || (i == 0 && line.toLowerCase(Locale.ROOT).startsWith("external"))) continue;
            String[] cols = line.split(",", -1);
            try {
                String txn = cols[0].trim();
                String clickRef = cols.length > 1 ? cols[1].trim() : "";
                Long sale = cols.length > 2 ? toMinor(cols[2]) : null;
                Long commission = cols.length > 3 ? toMinor(cols[3]) : null;
                AffiliateConversion.Status status = cols.length > 4 && !cols[4].isBlank()
                        ? AffiliateConversion.Status.valueOf(cols[4].trim().toUpperCase(Locale.ROOT))
                        : AffiliateConversion.Status.PENDING;
                Upsert u = upsertConversion(sf, txn, clickRef, sale, commission, status, AffiliateConversion.Source.IMPORT);
                if (u.matchedClick()) matched++;
                if (u.created()) created++; else updated++;
            } catch (RuntimeException e) {
                errors.add("Line " + (i + 1) + ": " + (e.getMessage() == null ? "invalid row" : e.getMessage()));
                if (errors.size() > 50) break;
            }
        }
        audit.record(admin, "AFFILIATE_CONVERSIONS_IMPORTED", "AffiliateConversion", null,
                "created=" + created + " updated=" + updated + " errors=" + errors.size());
        return new ImportResult(created, updated, matched, errors);
    }

    public record PostbackResult(String externalTxnId, String status, boolean created, boolean matchedClick) {
    }

    /**
     * Records a conversion reported by an affiliate network's server-to-server postback.
     * Amounts are in rupees (e.g. "1499.00"); unknown status words are treated as pending.
     * Repeated postbacks for the same transaction update the existing row.
     */
    @Transactional
    public PostbackResult recordPostback(Storefront sf, String txn, String clickRef, String sale, String commission,
                                         String status) {
        Long saleMinor;
        Long commissionMinor;
        try {
            saleMinor = toMinor(sale);
            commissionMinor = toMinor(commission);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("INVALID_AMOUNT", "sale and commission must be amounts like 1499.00");
        }
        if ((saleMinor != null && saleMinor < 0) || (commissionMinor != null && commissionMinor < 0)) {
            throw ApiException.badRequest("INVALID_AMOUNT", "Amounts cannot be negative");
        }
        AffiliateConversion.Status mapped = postbackStatus(status);
        try {
            Upsert u = upsertConversion(sf, txn, clickRef, saleMinor, commissionMinor, mapped,
                    AffiliateConversion.Source.POSTBACK);
            return new PostbackResult(txn.trim(), mapped.name(), u.created(), u.matchedClick());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_POSTBACK", e.getMessage());
        }
    }

    static AffiliateConversion.Status postbackStatus(String raw) {
        if (raw == null) return AffiliateConversion.Status.PENDING;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "approved", "approve", "confirmed", "validated", "accepted", "1" -> AffiliateConversion.Status.APPROVED;
            case "reversed", "rejected", "declined", "cancelled", "canceled", "void", "voided", "-1" ->
                    AffiliateConversion.Status.REVERSED;
            case "paid" -> AffiliateConversion.Status.PAID;
            default -> AffiliateConversion.Status.PENDING;
        };
    }

    private record Upsert(boolean created, boolean matchedClick) {
    }

    private Upsert upsertConversion(Storefront sf, String rawTxn, String rawClickRef, Long sale, Long commission,
                                    AffiliateConversion.Status status, AffiliateConversion.Source source) {
        String txn = rawTxn == null ? "" : rawTxn.trim();
        if (txn.isEmpty() || txn.length() > 120) throw new IllegalArgumentException("missing transaction id");
        String clickRef = rawClickRef == null ? "" : rawClickRef.trim();
        if (clickRef.length() > 40) throw new IllegalArgumentException("click reference is too long");
        Optional<AffiliateConversion> existing = conversions.findByStorefrontAndExternalTxnId(sf, txn);
        AffiliateConversion c = existing.orElseGet(() -> new AffiliateConversion(sf, txn, source));
        boolean matched = false;
        if (!clickRef.isEmpty()) {
            c.setClickRef(clickRef);
            var click = clicks.findByClickRef(clickRef).filter(k -> k.getStorefront() == sf);
            if (click.isPresent()) {
                matched = true;
                links.findById(click.get().getLinkId()).ifPresent(l -> c.setMerchantId(l.getMerchant().getId()));
            }
        }
        c.setSaleAmountMinor(sale);
        c.setCommissionMinor(commission);
        c.setStatus(status);
        conversions.save(c);
        return new Upsert(existing.isEmpty(), matched);
    }

    @Transactional(readOnly = true)
    public Report report(Storefront sf) {
        Instant now = Instant.now();
        long c7 = clicks.countSince(sf, now.minus(Duration.ofDays(7)));
        long c30 = clicks.countSince(sf, now.minus(Duration.ofDays(30)));
        Map<String, Long> byStatus = new LinkedHashMap<>();
        Map<String, Long> commission = new LinkedHashMap<>();
        for (Object[] row : conversions.summary(sf)) {
            byStatus.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
            commission.put(String.valueOf(row[0]), ((Number) row[2]).longValue());
        }
        List<LinkView> all = listLinks(sf);
        List<LinkView> top = all.stream()
                .sorted(Comparator.comparingLong(LinkView::clicks30d).reversed()).limit(10).toList();
        long stale = all.stream().filter(LinkView::priceStale).count();

        Map<Long, long[]> conv = new HashMap<>();
        for (Object[] row : conversions.byLinkForClicksSince(sf, now.minus(Duration.ofDays(30)),
                AffiliateConversion.Status.REVERSED)) {
            conv.put((Long) row[0], new long[]{((Number) row[1]).longValue(), ((Number) row[2]).longValue()});
        }
        List<PerformanceRow> products = new ArrayList<>();
        Map<String, long[]> byMerchant = new LinkedHashMap<>();
        for (LinkView l : all) {
            long[] cv = conv.getOrDefault(l.id(), new long[2]);
            if (l.clicks30d() == 0 && cv[0] == 0) continue;
            products.add(row(l.productId(), l.productTitle(), l.merchantName(), l.clicks30d(), cv[0], cv[1]));
            long[] m = byMerchant.computeIfAbsent(l.merchantName(), k -> new long[3]);
            m[0] += l.clicks30d();
            m[1] += cv[0];
            m[2] += cv[1];
        }
        Comparator<PerformanceRow> byValue = Comparator.comparingLong(PerformanceRow::commission30dMinor)
                .thenComparingLong(PerformanceRow::clicks30d).reversed();
        products.sort(byValue);
        List<PerformanceRow> merchantRows = new ArrayList<>();
        byMerchant.forEach((name, m) -> merchantRows.add(row(null, name, name, m[0], m[1], m[2])));
        merchantRows.sort(byValue);
        return new Report(c7, c30, byStatus, commission, top, stale,
                products.size() > 50 ? products.subList(0, 50) : products, merchantRows);
    }

    private static PerformanceRow row(Long productId, String label, String merchant, long clicks, long convs, long commission) {
        Double rate = clicks == 0 ? null : Math.round(convs * 10000.0 / clicks) / 100.0;
        Long perClick = clicks == 0 ? null : Math.round((double) commission / clicks);
        return new PerformanceRow(productId, label, merchant, clicks, convs, commission, rate, perClick);
    }

    @Transactional(readOnly = true)
    public long clicksSince(Storefront sf, Instant since) {
        return clicks.countSince(sf, since);
    }

    // ------------------------------------------------------------------ helpers

    private Map<Long, Long> clickCounts(Storefront sf, int days) {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : clicks.countByLinkSince(sf, Instant.now().minus(Duration.ofDays(days)))) {
            counts.put((Long) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    private String newCode() {
        for (int i = 0; i < 10; i++) {
            String code = Hashing.randomCode(8);
            if (!links.existsByCode(code)) return code;
        }
        throw new IllegalStateException("Could not generate a unique affiliate code");
    }

    private static Long toMinor(String value) {
        if (value == null || value.isBlank()) return null;
        return new BigDecimal(value.trim()).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    /** Crawlers, link previews and scripts: their visits are not counted as shopper clicks. */
    public static boolean isBot(String ua) {
        String u = ua == null ? "" : ua.toLowerCase(Locale.ROOT);
        return u.isEmpty() || u.contains("bot") || u.contains("crawl") || u.contains("spider") || u.contains("preview")
                || u.contains("facebookexternalhit") || u.contains("curl") || u.contains("wget") || u.contains("python-requests");
    }

    private static String refererHost(String referer) {
        if (referer == null || referer.isBlank()) return null;
        try {
            String host = URI.create(referer).getHost();
            return host == null ? null : host.substring(0, Math.min(host.length(), 253));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private LinkView toView(AffiliateLink l, String title, Map<Long, Long> counts) {
        AffiliateMerchant m = l.getMerchant();
        return new LinkView(l.getId(), l.getProductId(), title, l.getCode(), "/go/" + l.getCode(), l.getDestinationUrl(),
                m.getId(), m.getName(), m.getDomain(), l.getDisplayPriceMinor(), l.getListPriceMinor(), l.getPriceCheckedAt(),
                isStale(l, Instant.now()), m.isHidePrices(), l.getDisclosure(), l.isActive(), counts.getOrDefault(l.getId(), 0L),
                l.getHealthStatus(), l.getHealthCheckedAt(), l.getHealthDetail());
    }

    private static MerchantView toView(AffiliateMerchant m) {
        return new MerchantView(m.getId(), m.getName(), m.getDomain(), m.getSubIdParam(), m.isAllowHttp(), m.isActive(),
                m.isHidePrices(), m.getCreatedAt());
    }

    private static ConversionView toView(AffiliateConversion c) {
        return new ConversionView(c.getId(), c.getExternalTxnId(), c.getClickRef(), c.getMerchantId(), c.getSaleAmountMinor(),
                c.getCommissionMinor(), c.getStatus().name(), c.getSource().name(), c.getNote(), c.getReportedAt());
    }
}
