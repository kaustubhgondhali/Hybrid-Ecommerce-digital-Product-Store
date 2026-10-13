package com.hybrid.platform.catalog;

import com.hybrid.platform.affiliate.AffiliateService;
import com.hybrid.platform.catalog.CatalogEnums.ProductStatus;
import com.hybrid.platform.catalog.CatalogEnums.ProductType;
import com.hybrid.platform.catalog.CatalogEnums.SellingMethod;
import com.hybrid.platform.common.PageResponse;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.commerce.inventory.InventoryService;
import com.hybrid.platform.commerce.settings.SettingsService;
import com.hybrid.platform.commerce.settings.StoreSettings;
import com.hybrid.platform.delivery.DeliveryReadinessService;
import com.hybrid.platform.review.ReviewService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Public storefront read model. Prices, availability and affiliate links all come from the backend. */
@Service
public class CatalogPublicService {

    /** How many of the newest products the home-page sections pick from. */
    static final int HOME_POOL = 300;
    static final int SECTION_SIZE = 8;

    /**
     * A product tile. For partner offers the price is the best store's shown price ({@code checkPrice} when it may not
     * be shown), {@code compareAtMinor} the original price when there is a discount, and {@code offerCount} how many
     * stores carry the product.
     */
    public record ProductCard(Long id, String slug, String title, String summary, String productType,
                              String sellingMethod, String imageUrl, Long priceMinor, Long compareAtMinor,
                              Boolean inStock, String merchantName, String goUrl,
                              Integer discountPercent, boolean checkPrice, Instant priceCheckedAt, int offerCount,
                              Integer expertRating, boolean lowest30d, Integer dropPercent) {
    }

    public record VariantView(Long id, String name, String sku, Long priceMinor, Long compareAtMinor, boolean available) {
    }

    /** {@code url} is the original, {@code largeUrl} the product-page size, {@code thumbUrl} the grid size. */
    public record ImageView(String url, String largeUrl, String thumbUrl, String alt, Integer width, Integer height) {
    }

    /** A gallery video; {@code posterUrl} may be null when the admin's browser could not capture a frame. */
    public record VideoView(String url, String posterUrl, String title, String contentType, Integer durationSeconds,
                            Integer width, Integer height, Instant uploadedAt) {
    }

    public record CategoryView(Long id, String name, String slug) {
    }

    public record AffiliateOffer(Long linkId, String merchantName, String merchantDomain, Long displayPriceMinor,
                                 Long listPriceMinor, Integer discountPercent, Instant priceCheckedAt, boolean priceStale,
                                 boolean priceHidden, String disclosure, String goUrl) {
    }

    /** Shown prices over the last 30 days; {@code lowest30d} only when the price really was higher at some point. */
    public record PriceStats(Long low30Minor, Long high30Minor, boolean lowest30d, Integer dropPercent) {
    }

    /** One line of the specifications table ("Battery" / "30 hours"). */
    public record Spec(String name, String value) {
    }

    /** As-you-type search: a few matching products and categories. */
    public record Suggestions(List<ProductCard> products, List<CategoryView> categories) {
    }

    /** A partner store with live deals in this storefront. */
    public record StoreView(String name, String domain, int deals) {
    }

    /** {@code affiliate} is the best offer; {@code offers} lists every store, best first. */
    public record ProductDetail(Long id, String slug, String title, String summary, String description, String brand,
                                CategoryView category, String productType, String sellingMethod, String digitalFormat,
                                String deliveryKind, List<ImageView> images, List<VariantView> variants,
                                AffiliateOffer affiliate, String currency,
                                List<AffiliateOffer> offers, List<String> highlights, List<String> pros, List<String> cons,
                                String verdict, Integer expertRating, PriceStats priceStats, List<ProductCard> similar,
                                List<VideoView> videos, List<Spec> specs, long clicks7d) {
    }

    public record CategoryTile(String name, String slug, long products, String imageUrl) {
    }

    /** Home-page sections; each is empty until there is real data for it (discounts, price cuts, clicks). */
    public record Home(List<ProductCard> featured, List<ProductCard> topDeals, List<ProductCard> priceDrops,
                       List<ProductCard> trending, List<CategoryTile> categories, DealOfDay dealOfDay) {
    }

    /** The product the team picked as Deal of the Day, until {@code endsAt} (shown with a countdown). */
    public record DealOfDay(ProductCard product, Instant endsAt) {
    }

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final InventoryService inventory;
    private final AffiliateService affiliate;
    private final DeliveryReadinessService delivery;
    private final ProductSearch search;
    private final ReviewService reviews;
    private final SettingsService settings;

    public CatalogPublicService(ProductRepository products, CategoryRepository categories, InventoryService inventory,
                                AffiliateService affiliate, DeliveryReadinessService delivery, ProductSearch search,
                                ReviewService reviews, SettingsService settings) {
        this.settings = settings;
        this.products = products;
        this.categories = categories;
        this.inventory = inventory;
        this.affiliate = affiliate;
        this.delivery = delivery;
        this.search = search;
        this.reviews = reviews;
    }

    /** Sorts and filters that need each product's live best offer, so they are applied after building the cards. */
    private static final Set<String> CARD_SORTS = Set.of("price_asc", "price_desc", "discount", "popular", "rating");
    /** Most products considered when sorting or filtering by price, discount, store or popularity. */
    static final int SCAN_LIMIT = 2000;

    /**
     * Catalog listing with shopper sorts (price, biggest discount, most popular, top rated) and filters (store,
     * minimum discount, price range). Plain listings use the database directly; the rest are computed on the cards.
     */
    @Transactional(readOnly = true)
    public PageResponse<ProductCard> browse(Storefront sf, String q, String categorySlug, ProductType type, SellingMethod method,
                                            String sort, String store, Integer minDiscount, Long minPriceMinor, Long maxPriceMinor,
                                            int page, int size) {
        boolean cardLevel = (sort != null && CARD_SORTS.contains(sort)) || (store != null && !store.isBlank())
                || (minDiscount != null && minDiscount > 0) || minPriceMinor != null || maxPriceMinor != null;
        if (!cardLevel) return list(sf, q, categorySlug, type, method, sort, page, size);
        if (settings.affiliateOnly(sf)) {
            if (method != null && method.onSite()) return PageResponse.of(Page.empty(), x -> null);
            method = SellingMethod.AFFILIATE;
        }
        Long categoryId = null;
        if (categorySlug != null && !categorySlug.isBlank()) {
            categoryId = categories.findByStorefrontAndSlug(sf, categorySlug).filter(Category::isActive)
                    .map(Category::getId).orElse(-1L);
        }
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 48));
        if (categoryId != null && categoryId < 0) return PageResponse.of(Page.empty(pageable), x -> null);
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase(Locale.ROOT).replace("%", "").replace("_", "") + "%";
        PageRequest scan = PageRequest.of(0, SCAN_LIMIT, Sort.by(Sort.Direction.DESC, "publishedAt"));
        Optional<List<Long>> ranked = like == null ? Optional.empty() : search.rankedIds(sf, q.trim(), categoryId, type, method);
        List<Product> matches = ranked.isPresent()
                ? rankedPage(ranked.get().subList(0, Math.min(ranked.get().size(), SCAN_LIMIT)), scan).getContent()
                : products.search(sf, ProductStatus.PUBLISHED, like, categoryId, type, method, scan).getContent();
        List<ProductCard> cards = cardsFor(matches.stream().filter(this::visible).toList());

        if (store != null && !store.isBlank()) {
            String wanted = store.trim().toLowerCase(Locale.ROOT);
            Map<Long, List<AffiliateService.OfferView>> lists = affiliate.offerLists(cards.stream().map(ProductCard::id).toList());
            cards = cards.stream().filter(c -> lists.getOrDefault(c.id(), List.of()).stream().anyMatch(o ->
                    wanted.equalsIgnoreCase(o.merchantDomain()) || wanted.equalsIgnoreCase(o.merchantName()))).toList();
        }
        if (minDiscount != null && minDiscount > 0) {
            cards = cards.stream().filter(c -> c.discountPercent() != null && c.discountPercent() >= minDiscount).toList();
        }
        if (minPriceMinor != null || maxPriceMinor != null) {
            cards = cards.stream().filter(c -> c.priceMinor() != null
                    && (minPriceMinor == null || c.priceMinor() >= minPriceMinor)
                    && (maxPriceMinor == null || c.priceMinor() <= maxPriceMinor)).toList();
        }
        Comparator<ProductCard> order = null;
        if ("price_asc".equals(sort)) {
            order = Comparator.comparing(ProductCard::priceMinor, Comparator.nullsLast(Comparator.naturalOrder()));
        } else if ("price_desc".equals(sort)) {
            order = Comparator.comparing(ProductCard::priceMinor, Comparator.nullsLast(Comparator.reverseOrder()));
        } else if ("discount".equals(sort)) {
            order = Comparator.comparing(ProductCard::discountPercent, Comparator.nullsLast(Comparator.reverseOrder()));
        } else if ("popular".equals(sort)) {
            Map<Long, Long> clicks = affiliate.productClickCounts(sf, 30);
            order = Comparator.comparing((ProductCard c) -> clicks.getOrDefault(c.id(), 0L)).reversed();
        } else if ("rating".equals(sort)) {
            Map<Long, ReviewService.Rating> ratings = reviews.ratings(cards.stream().map(ProductCard::id).toList());
            order = Comparator.comparing((ProductCard c) -> score(c, ratings.get(c.id()))).reversed();
        }
        if (order != null) cards = cards.stream().sorted(order).toList();
        int from = (int) Math.min(pageable.getOffset(), cards.size());
        int to = Math.min(from + pageable.getPageSize(), cards.size());
        List<ProductCard> slice = cards.subList(from, to);
        return PageResponse.of(new org.springframework.data.domain.PageImpl<>(slice, pageable, cards.size()), c -> c);
    }

    /** Our rating when we gave one, otherwise the customers' average (out of 5); unrated products last. */
    private static double score(ProductCard c, ReviewService.Rating r) {
        if (c.expertRating() != null) return c.expertRating() / 10.0;
        return r == null || r.count() == 0 ? -1 : r.average();
    }

    /** Up to 6 products and 4 categories for the search box, matched as the shopper types. */
    @Transactional(readOnly = true)
    public Suggestions suggest(Storefront sf, String q) {
        String term = q == null ? "" : q.strip();
        if (term.length() < 2) return new Suggestions(List.of(), List.of());
        String lower = term.toLowerCase(Locale.ROOT);
        List<CategoryView> cats = categories(sf).stream()
                .filter(c -> c.name().toLowerCase(Locale.ROOT).contains(lower)).limit(4).toList();
        return new Suggestions(list(sf, term, null, null, null, null, 0, 6).items(), cats);
    }

    /** Partner stores that have live deals here, most deals first (for the store filter and store pages). */
    @Transactional(readOnly = true)
    public List<StoreView> stores(Storefront sf) {
        List<Long> ids = products.findByStorefrontAndStatusOrderByPublishedAtDesc(sf, ProductStatus.PUBLISHED).stream()
                .filter(p -> p.getSellingMethod() == SellingMethod.AFFILIATE).map(Product::getId).toList();
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (List<AffiliateService.OfferView> list : affiliate.offerLists(ids).values()) {
            list.forEach(o -> names.putIfAbsent(o.merchantDomain(), o.merchantName()));
            list.stream().map(AffiliateService.OfferView::merchantDomain).distinct().forEach(d -> counts.merge(d, 1, Integer::sum));
        }
        return names.entrySet().stream().map(e -> new StoreView(e.getValue(), e.getKey(), counts.getOrDefault(e.getKey(), 0)))
                .sorted(Comparator.comparingInt(StoreView::deals).reversed().thenComparing(StoreView::name))
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductCard> list(Storefront sf, String q, String categorySlug, ProductType type,
                                          SellingMethod method, String sort, int page, int size) {
        if (settings.affiliateOnly(sf)) {
            if (method != null && method.onSite()) {
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
        Map<Long, ProductCard> cards = byId(cardsFor(result.getContent()));
        return PageResponse.of(result, p -> cards.get(p.getId()));
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
                .filter(p -> p.getStorefront() == sf && p.getStatus() == ProductStatus.PUBLISHED && visible(p))
                .forEach(p -> byId.put(p.getId(), p));
        return cardsFor(ids.stream().map(byId::get).filter(Objects::nonNull).toList());
    }

    @Transactional(readOnly = true)
    public ProductDetail detail(Storefront sf, String slug) {
        Product p = products.findByStorefrontAndSlugAndStatus(sf, slug, ProductStatus.PUBLISHED)
                .orElseThrow(() -> ApiException.notFound("PRODUCT_NOT_FOUND", "Product not found"));
        if (!visible(p)) {
            throw ApiException.notFound("PRODUCT_NOT_FOUND", "Product not found");
        }
        List<ProductVariant> active = p.activeVariants();
        Map<Long, Integer> stock = p.isOwnStock()
                ? inventory.availability(active.stream().map(ProductVariant::getId).toList()) : Map.of();
        boolean digitalAvailable = p.getProductType() != ProductType.DIGITAL || !p.isOnSite()
                || !DeliveryReadinessService.needsLicense(p.getDeliveryKind()) || delivery.availableLicenses(p.getId()) > 0;
        List<AffiliateService.OfferView> offerViews = p.getSellingMethod() == SellingMethod.AFFILIATE
                ? affiliate.offerLists(List.of(p.getId())).getOrDefault(p.getId(), List.of()) : List.of();
        AffiliateService.OfferView best = offerViews.isEmpty() ? null : offerViews.get(0);
        List<AffiliateOffer> offers = offerViews.stream().map(CatalogPublicService::offer).toList();
        PriceStats stats = null;
        if (best != null && best.displayPriceMinor() != null) {
            AffiliateService.PriceInsight i = affiliate.insights(Map.of(p.getId(), best)).get(p.getId());
            if (i != null) stats = new PriceStats(i.low30Minor(), i.high30Minor(), i.lowest30d(), i.dropPercent7d());
        }
        List<VariantView> variants = p.isOnSite()
                ? active.stream().map(v -> new VariantView(v.getId(), v.getName(), v.getSku(), v.getPriceMinor(),
                v.getCompareAtMinor(), p.isOwnStock() ? stock.getOrDefault(v.getId(), 0) > 0 : digitalAvailable)).toList()
                : active.stream().map(v -> new VariantView(v.getId(), v.getName(), null,
                best == null ? null : best.displayPriceMinor(), null, best != null)).toList();
        CategoryView category = p.getCategoryId() == null ? null : categories.findById(p.getCategoryId())
                .filter(Category::isActive).map(c -> new CategoryView(c.getId(), c.getName(), c.getSlug())).orElse(null);
        return new ProductDetail(p.getId(), p.getSlug(), p.getTitle(), p.getSummary(), p.getDescription(), p.getBrand(),
                category, p.getProductType().name(), p.getSellingMethod().name(), p.getDigitalFormat(),
                p.getDeliveryKind() == null ? null : p.getDeliveryKind().name(),
                p.getImages().stream().map(i -> new ImageView(i.url(), i.largeUrl(), i.thumbUrl(),
                        i.getAltText() == null ? p.getTitle() : i.getAltText(), i.getWidth(), i.getHeight())).toList(),
                variants, offers.isEmpty() ? null : offers.get(0), "INR",
                offers, items(p.getHighlights()), items(p.getPros()), items(p.getCons()), p.getVerdict(), p.getExpertRating(),
                stats, similar(p),
                p.getVideos().stream().map(v -> new VideoView(v.url(), v.posterUrl(), v.getTitle() == null ? p.getTitle() : v.getTitle(),
                        v.contentType(), v.getDurationSeconds(), v.getWidth(), v.getHeight(), v.getCreatedAt())).toList(),
                specs(p.getSpecs()),
                p.getSellingMethod() == SellingMethod.AFFILIATE ? affiliate.clicksSince(p.getId(), 7) : 0);
    }

    static List<Spec> specs(String text) {
        return items(text).stream().map(line -> {
            int colon = line.indexOf(':');
            return colon < 0 ? new Spec(line, "") : new Spec(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }).toList();
    }

    /** Active categories that have at least one published product (empty ones stay out of the shop menus). */
    @Transactional(readOnly = true)
    public List<CategoryView> categories(Storefront sf) {
        Set<Long> withProducts = products.countByCategory(sf, ProductStatus.PUBLISHED,
                        settings.affiliateOnly(sf) ? SellingMethod.AFFILIATE : null).stream()
                .map(r -> (Long) r[0]).collect(Collectors.toSet());
        return categories.findByStorefrontAndActiveTrueOrderBySortOrderAscNameAsc(sf).stream()
                .filter(c -> withProducts.contains(c.getId()))
                .map(c -> new CategoryView(c.getId(), c.getName(), c.getSlug())).toList();
    }

    /**
     * Home-page sections built from real data: biggest discounts, recent price cuts, most-clicked deals and categories.
     * Picks from the {@value #HOME_POOL} newest products.
     */
    @Transactional(readOnly = true)
    public Home home(Storefront sf) {
        SellingMethod method = settings.affiliateOnly(sf) ? SellingMethod.AFFILIATE : null;
        List<Product> pool = products.search(sf, ProductStatus.PUBLISHED, null, null, null, method,
                PageRequest.of(0, HOME_POOL, Sort.by(Sort.Direction.DESC, "publishedAt"))).getContent();
        List<ProductCard> cards = cardsFor(pool);
        Map<Long, ProductCard> byId = byId(cards);

        List<ProductCard> topDeals = cards.stream().filter(c -> c.discountPercent() != null && c.discountPercent() >= 5)
                .sorted(Comparator.comparing(ProductCard::discountPercent).reversed()).limit(SECTION_SIZE).toList();
        List<ProductCard> drops = affiliate.recentPriceDrops(sf, Instant.now().minus(Duration.ofDays(7))).stream()
                .map(d -> byId.get(d.productId())).filter(c -> c != null && c.dropPercent() != null)
                .limit(SECTION_SIZE).toList();
        List<ProductCard> trending = affiliate.trendingProductIds(sf, 7, SECTION_SIZE * 2).stream()
                .map(byId::get).filter(Objects::nonNull).limit(SECTION_SIZE).toList();

        List<ProductCard> featured = withImages(trending);
        if (featured.size() < 2) featured = withImages(topDeals);
        if (featured.size() < 2) featured = withImages(cards);

        Map<Long, Long> counts = new HashMap<>();
        Map<Long, String> images = new HashMap<>();
        for (Product p : pool) {
            if (p.getCategoryId() == null) continue;
            counts.merge(p.getCategoryId(), 1L, Long::sum);
            if (!images.containsKey(p.getCategoryId()) && !p.getImages().isEmpty()) {
                images.put(p.getCategoryId(), p.getImages().get(0).thumbUrl());
            }
        }
        List<CategoryTile> tiles = categories.findByStorefrontAndActiveTrueOrderBySortOrderAscNameAsc(sf).stream()
                .filter(c -> counts.containsKey(c.getId()))
                .map(c -> new CategoryTile(c.getName(), c.getSlug(), counts.get(c.getId()), images.get(c.getId()))).toList();
        return new Home(featured.stream().limit(SECTION_SIZE).toList(), topDeals, drops, trending, tiles, dealOfDay(sf));
    }

    /** Today's pick, if one is set, still running and the product is still on sale here. */
    @Transactional(readOnly = true)
    public DealOfDay dealOfDay(Storefront sf) {
        StoreSettings s = settings.entity(sf);
        if (s.getDealOfDayProductId() == null || s.getDealOfDayEndsAt() == null || !s.getDealOfDayEndsAt().isAfter(Instant.now())) {
            return null;
        }
        List<ProductCard> card = cards(sf, List.of(s.getDealOfDayProductId()));
        return card.isEmpty() ? null : new DealOfDay(card.get(0), s.getDealOfDayEndsAt());
    }

    // ------------------------------------------------------------------ helpers

    private boolean visible(Product p) {
        return p.getSellingMethod() == SellingMethod.AFFILIATE || !settings.affiliateOnly(p.getStorefront());
    }

    private List<ProductCard> similar(Product p) {
        SellingMethod method = settings.affiliateOnly(p.getStorefront()) ? SellingMethod.AFFILIATE : null;
        PageRequest page = PageRequest.of(0, 9, Sort.by(Sort.Direction.DESC, "publishedAt"));
        LinkedHashMap<Long, Product> pool = new LinkedHashMap<>();
        if (p.getCategoryId() != null) {
            products.search(p.getStorefront(), ProductStatus.PUBLISHED, null, p.getCategoryId(), null, method, page)
                    .forEach(x -> pool.putIfAbsent(x.getId(), x));
        }
        if (pool.size() < 5) {
            products.search(p.getStorefront(), ProductStatus.PUBLISHED, null, null, null, method, page)
                    .forEach(x -> pool.putIfAbsent(x.getId(), x));
        }
        pool.remove(p.getId());
        return cardsFor(pool.values().stream().limit(4).toList());
    }

    private List<ProductCard> cardsFor(List<Product> list) {
        if (list.isEmpty()) return List.of();
        Map<Long, AffiliateService.OfferView> offers = affiliate.offers(
                list.stream().filter(p -> p.getSellingMethod() == SellingMethod.AFFILIATE).map(Product::getId).toList());
        Map<Long, AffiliateService.PriceInsight> insights = affiliate.insights(offers);
        Map<Long, Integer> stock = inventory.availability(list.stream().filter(Product::isOwnStock)
                .flatMap(p -> p.activeVariants().stream()).map(ProductVariant::getId).toList());
        return list.stream().map(p -> card(p, offers.get(p.getId()), insights.get(p.getId()), stock)).toList();
    }

    private ProductCard card(Product p, AffiliateService.OfferView offer, AffiliateService.PriceInsight insight,
                             Map<Long, Integer> stock) {
        String image = p.getImages().isEmpty() ? null : p.getImages().get(0).thumbUrl();
        if (p.getSellingMethod() == SellingMethod.AFFILIATE) {
            Long price = offer == null ? null : offer.displayPriceMinor();
            Integer discount = offer == null ? null : offer.discountPercent();
            return new ProductCard(p.getId(), p.getSlug(), p.getTitle(), p.getSummary(), p.getProductType().name(),
                    p.getSellingMethod().name(), image, price, discount == null ? null : offer.listPriceMinor(), null,
                    offer == null ? null : offer.merchantName(), offer == null ? null : offer.goUrl(),
                    discount, offer != null && price == null, price == null ? null : offer.priceCheckedAt(),
                    offer == null ? 0 : offer.offerCount(), p.getExpertRating(),
                    insight != null && insight.lowest30d(), insight == null ? null : insight.dropPercent7d());
        }
        List<ProductVariant> active = p.activeVariants();
        ProductVariant cheapest = active.stream().filter(v -> v.getPriceMinor() != null)
                .min(Comparator.comparing(ProductVariant::getPriceMinor)).orElse(null);
        Boolean inStock = p.isOwnStock()
                ? active.stream().anyMatch(v -> stock.getOrDefault(v.getId(), 0) > 0) : Boolean.TRUE;
        Long price = cheapest == null ? null : cheapest.getPriceMinor();
        Long compareAt = cheapest == null ? null : cheapest.getCompareAtMinor();
        return new ProductCard(p.getId(), p.getSlug(), p.getTitle(), p.getSummary(), p.getProductType().name(),
                p.getSellingMethod().name(), image, price, compareAt, inStock, null, null,
                AffiliateService.discount(price, compareAt), false, null, 0, p.getExpertRating(), false, null);
    }

    private static AffiliateOffer offer(AffiliateService.OfferView o) {
        return new AffiliateOffer(o.linkId(), o.merchantName(), o.merchantDomain(), o.displayPriceMinor(), o.listPriceMinor(),
                o.discountPercent(), o.priceCheckedAt(), o.priceStale(), o.priceHidden(), o.disclosure(), o.goUrl());
    }

    private static List<ProductCard> withImages(List<ProductCard> cards) {
        return cards.stream().filter(c -> c.imageUrl() != null).toList();
    }

    private static Map<Long, ProductCard> byId(List<ProductCard> cards) {
        return cards.stream().collect(Collectors.toMap(ProductCard::id, Function.identity(), (a, b) -> a));
    }

    static List<String> items(String text) {
        return text == null || text.isBlank() ? List.of() : text.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
