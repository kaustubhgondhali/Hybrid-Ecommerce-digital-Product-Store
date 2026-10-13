package com.hybrid.platform.seo;

import com.hybrid.platform.catalog.CatalogEnums.ProductStatus;
import com.hybrid.platform.catalog.CatalogPublicService;
import com.hybrid.platform.catalog.Category;
import com.hybrid.platform.catalog.CategoryRepository;
import com.hybrid.platform.catalog.Product;
import com.hybrid.platform.catalog.ProductRepository;
import com.hybrid.platform.commerce.settings.SettingsService;
import com.hybrid.platform.commerce.settings.StoreSettings;
import com.hybrid.platform.common.AppProperties;
import com.hybrid.platform.common.PageResponse;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.content.ContentMarkup;
import com.hybrid.platform.content.ContentPage;
import com.hybrid.platform.content.ContentService;
import com.hybrid.platform.guides.Guide;
import com.hybrid.platform.guides.GuideService;
import com.hybrid.platform.review.ReviewService;
import com.hybrid.platform.offers.OfferService;
import com.hybrid.platform.stories.WebStoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.*;

import static com.hybrid.platform.seo.SeoPage.esc;

/** Builds the SEO head tags and crawlable HTML for public storefront pages. */
@Service
public class SeoPageService {

    static final Map<Storefront, String[]> DEFAULTS = Map.of(
            Storefront.SHOP, new String[]{"Affiliate deals on audio, tech and lifestyle in India",
                    "Curated affiliate picks from trusted partner stores. You buy on their site — we may earn a commission when you do."},
            Storefront.DIGITAL, new String[]{"Affiliate software, courses and e-book deals",
                    "Hand-picked digital offers from partner merchants. Continue on their store to buy — fulfilment is theirs."});

    private static final String LOGO = "/assets/img/%s-logo.png";

    private final SettingsService settings;
    private final ProductRepository products;
    private final CategoryRepository categories;
    private final CatalogPublicService catalog;
    private final ContentService content;
    private final ReviewService reviews;
    private final WebStoryService stories;
    private final OfferService offers;
    private final GuideService guides;
    private final AppProperties props;

    public SeoPageService(SettingsService settings, ProductRepository products, CategoryRepository categories,
                          CatalogPublicService catalog, ContentService content, ReviewService reviews,
                          WebStoryService stories, OfferService offers, GuideService guides, AppProperties props) {
        this.offers = offers;
        this.guides = guides;
        this.settings = settings;
        this.products = products;
        this.categories = categories;
        this.catalog = catalog;
        this.content = content;
        this.reviews = reviews;
        this.stories = stories;
        this.props = props;
    }

    public String baseUrl() {
        String b = props.baseUrl() == null ? "" : props.baseUrl().trim();
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    public String abs(String path) {
        return path == null ? null : path.startsWith("http") ? path : baseUrl() + path;
    }

    // ------------------------------------------------------------------ store home

    @Transactional
    public SeoPage home(Storefront sf, Map<String, String> query) {
        StoreSettings s = settings.entity(sf);
        int page = pageParam(query);
        boolean filtered = query.keySet().stream().anyMatch(k -> !k.equals("page") && !query.get(k).isBlank());
        String home = "/" + sf.path();
        String title = s.getSeoTitle() != null ? s.getSeoTitle() : s.getStoreName() + " | " + DEFAULTS.get(sf)[0];
        String description = s.getSeoDescription() != null ? s.getSeoDescription() : DEFAULTS.get(sf)[1];

        PageResponse<CatalogPublicService.ProductCard> list = catalog.list(sf, null, null, null, null, null, page, 24);
        List<CatalogPublicService.CategoryView> cats = catalog.categories(sf);

        StringBuilder body = new StringBuilder("<div class=\"container ssr\">");
        body.append("<h1>").append(esc(s.getStoreName())).append("</h1>");
        body.append("<p class=\"muted\">").append(esc(description)).append("</p>");
        if (!cats.isEmpty()) {
            body.append("<nav class=\"ssr-links\" aria-label=\"Categories\">");
            for (var c : cats) {
                body.append("<a href=\"").append(esc(home + "/c/" + c.slug())).append("\">").append(esc(c.name())).append("</a>");
            }
            body.append("</nav>");
        }
        body.append(grid(sf, list.items()));
        body.append(pager(home, page, list.totalPages()));
        List<WebStoryService.StoryCard> latestStories = page == 0 ? stories.cards(sf, 8) : List.of();
        if (!latestStories.isEmpty()) {
            body.append("<nav class=\"ssr-links\" aria-label=\"Web Stories\"><a href=\"").append(esc(home + "/stories")).append("\">Web Stories</a>");
            for (var w : latestStories) {
                body.append("<a href=\"").append(esc(w.url())).append("\">").append(esc(w.title())).append("</a>");
            }
            body.append("</nav>");
        }
        body.append("</div>");

        SeoPage p = base(sf, s)
                .title(page > 0 ? title + " - Page " + (page + 1) : title)
                .description(description)
                .canonical(abs(home + (page > 0 ? "?page=" + page : "")))
                .noindex(filtered)
                .image(abs(LOGO.formatted(sf == Storefront.SHOP ? "elaashop" : "elaatech")))
                .body(body.toString());
        Map<String, Object> org = new LinkedHashMap<>();
        org.put("@context", "https://schema.org");
        org.put("@type", "Organization");
        org.put("name", s.getSellerLegalName() != null ? s.getSellerLegalName() : s.getStoreName());
        org.put("url", abs(home));
        org.put("logo", abs(LOGO.formatted(sf == Storefront.SHOP ? "elaashop" : "elaatech")));
        if (s.getSupportEmail() != null) {
            org.put("contactPoint", Map.of("@type", "ContactPoint", "contactType", "customer support",
                    "email", s.getSupportEmail(), "areaServed", "IN"));
        }
        p.jsonLd(org);
        Map<String, Object> site = new LinkedHashMap<>();
        site.put("@context", "https://schema.org");
        site.put("@type", "WebSite");
        site.put("name", s.getStoreName());
        site.put("url", abs(home));
        site.put("potentialAction", Map.of("@type", "SearchAction",
                "target", Map.of("@type", "EntryPoint", "urlTemplate", abs(home) + "?q={search_term_string}"),
                "query-input", "required name=search_term_string"));
        p.jsonLd(site);
        if (!list.items().isEmpty()) p.jsonLd(itemList(sf, list.items()));
        return p;
    }

    // ------------------------------------------------------------------ category

    @Transactional
    public Optional<SeoPage> category(Storefront sf, String slug, Map<String, String> query) {
        Optional<Category> found = categories.findByStorefrontAndSlug(sf, slug).filter(Category::isActive);
        if (found.isEmpty()) return Optional.empty();
        Category c = found.get();
        StoreSettings s = settings.entity(sf);
        int page = pageParam(query);
        String path = "/" + sf.path() + "/c/" + c.getSlug();
        String title = c.getMetaTitle() != null ? c.getMetaTitle() : c.getName() + " | " + s.getStoreName();
        String description = c.getMetaDescription() != null ? c.getMetaDescription()
                : c.getDescription() != null ? truncate(ContentMarkup.toPlainText(c.getDescription()), 160)
                : "Shop " + c.getName() + " at " + s.getStoreName() + ". " + DEFAULTS.get(sf)[1];
        PageResponse<CatalogPublicService.ProductCard> list = catalog.list(sf, null, c.getSlug(), null, null, null, page, 24);

        StringBuilder body = new StringBuilder("<div class=\"container ssr\">");
        body.append(breadcrumb(sf, s, List.of(Map.entry(c.getName(), path))));
        body.append("<h1>").append(esc(c.getName())).append("</h1>");
        if (c.getDescription() != null) body.append("<div class=\"content-body\">").append(ContentMarkup.toHtml(c.getDescription())).append("</div>");
        body.append(grid(sf, list.items()));
        body.append(pager(path, page, list.totalPages()));
        body.append("</div>");

        SeoPage p = base(sf, s)
                .title(page > 0 ? title + " - Page " + (page + 1) : title)
                .description(description)
                .canonical(abs(path + (page > 0 ? "?page=" + page : "")))
                .noindex(list.items().isEmpty() || query.keySet().stream().anyMatch(k -> !k.equals("page")))
                .body(body.toString());
        p.jsonLd(breadcrumbLd(sf, s, List.of(Map.entry(c.getName(), path))));
        if (!list.items().isEmpty()) p.jsonLd(itemList(sf, list.items()));
        return Optional.of(p);
    }

    // ------------------------------------------------------------------ product

    @Transactional
    public Optional<SeoPage> product(Storefront sf, String slug) {
        Optional<Product> found = products.findByStorefrontAndSlugAndStatus(sf, slug, ProductStatus.PUBLISHED);
        if (found.isEmpty()) return Optional.empty();
        Product entity = found.get();
        CatalogPublicService.ProductDetail d = catalog.detail(sf, slug);
        StoreSettings s = settings.entity(sf);
        String path = "/" + sf.path() + "/p/" + d.slug();
        String title = entity.getMetaTitle() != null ? entity.getMetaTitle() : d.title() + " | " + s.getStoreName();
        String description = productDescription(entity);
        String image = d.images().isEmpty() ? null : abs(d.images().get(0).largeUrl());
        ReviewService.Rating rating = reviews.rating(entity.getId());

        Long low = null, high = null;
        boolean anyAvailable = false;
        boolean partnerOffer = "AFFILIATE".equals(d.sellingMethod());
        for (var v : d.variants()) {
            // Partner offers are described by the partner stores' offers below, not as our own sale.
            if (partnerOffer || v.priceMinor() == null) continue;
            low = low == null ? v.priceMinor() : Math.min(low, v.priceMinor());
            high = high == null ? v.priceMinor() : Math.max(high, v.priceMinor());
            anyAvailable |= v.available();
        }
        Long affiliatePrice = d.affiliate() == null ? null : d.affiliate().displayPriceMinor();

        List<Map.Entry<String, String>> trail = new ArrayList<>();
        if (d.category() != null) trail.add(Map.entry(d.category().name(), "/" + sf.path() + "/c/" + d.category().slug()));
        trail.add(Map.entry(d.title(), path));

        StringBuilder body = new StringBuilder("<div class=\"container ssr\">");
        body.append(breadcrumb(sf, s, trail));
        body.append("<div class=\"product-detail\"><div class=\"gallery\"><div class=\"main\">");
        if (!d.images().isEmpty()) {
            var img = d.images().get(0);
            body.append("<img src=\"").append(esc(img.largeUrl())).append("\" alt=\"").append(esc(img.alt())).append('"');
            if (img.width() != null && img.height() != null) {
                int w = Math.min(img.width(), 1200);
                body.append(" width=\"").append(w).append("\" height=\"")
                        .append(Math.max(1, Math.round(img.height() * (w / (float) img.width())))).append('"');
            }
            body.append(" fetchpriority=\"high\">");
        }
        body.append("</div></div><div>");
        body.append("<h1>").append(esc(d.title())).append("</h1>");
        if (d.brand() != null) body.append("<p class=\"muted\">by ").append(esc(d.brand())).append("</p>");
        if (rating.count() > 0) {
            body.append("<p class=\"rating-line\">").append(stars(rating.average())).append(' ')
                    .append(rating.average()).append(" / 5 (").append(rating.count()).append(rating.count() == 1 ? " review" : " reviews").append(")</p>");
        }
        Long shown = low != null ? low : affiliatePrice;
        if (shown != null) {
            body.append("<p class=\"price\">").append(esc(money(shown))).append("</p>");
        } else if (d.affiliate() != null) {
            body.append("<p class=\"price\">Check price on ").append(esc(d.affiliate().merchantName())).append("</p>");
        }
        if (d.summary() != null) body.append("<p>").append(esc(d.summary())).append("</p>");
        appendList(body, "Highlights", "highlights", d.highlights());
        if (d.verdict() != null) body.append("<h2>Our verdict</h2><p>").append(esc(d.verdict())).append("</p>");
        appendList(body, "Pros", "pros", d.pros());
        appendList(body, "Cons", "cons", d.cons());
        if (d.description() != null) {
            body.append("<h2>Description</h2><div class=\"description\">").append(esc(d.description())).append("</div>");
        }
        body.append("</div></div></div>");

        SeoPage p = base(sf, s).title(title).description(description).canonical(abs(path))
                .noindex(entity.isNoindex()).ogType("product").image(image).body(body.toString());
        if (shown != null) {
            p.meta("product:price:amount", decimal(shown)).meta("product:price:currency", "INR");
        }

        Map<String, Object> ld = new LinkedHashMap<>();
        ld.put("@context", "https://schema.org");
        ld.put("@type", "Product");
        ld.put("name", d.title());
        ld.put("description", description);
        ld.put("url", abs(path));
        if (!d.images().isEmpty()) ld.put("image", d.images().stream().map(i -> abs(i.url())).toList());
        if (d.brand() != null) ld.put("brand", Map.of("@type", "Brand", "name", d.brand()));
        if (d.category() != null) ld.put("category", d.category().name());
        d.variants().stream().map(CatalogPublicService.VariantView::sku).filter(Objects::nonNull).findFirst()
                .ifPresent(sku -> ld.put("sku", sku));
        if (low != null) {
            Map<String, Object> offer = new LinkedHashMap<>();
            if (low.equals(high)) {
                offer.put("@type", "Offer");
                offer.put("price", decimal(low));
            } else {
                offer.put("@type", "AggregateOffer");
                offer.put("lowPrice", decimal(low));
                offer.put("highPrice", decimal(high));
                offer.put("offerCount", d.variants().size());
            }
            offer.put("priceCurrency", "INR");
            offer.put("availability", anyAvailable ? "https://schema.org/InStock" : "https://schema.org/OutOfStock");
            offer.put("itemCondition", "https://schema.org/NewCondition");
            offer.put("url", abs(path));
            offer.put("seller", Map.of("@type", "Organization", "name", s.getStoreName()));
            ld.put("offers", offer);
        } else if (affiliatePrice != null) {
            // Only stores whose prices may be shown take part; hidden prices never reach structured data either.
            List<CatalogPublicService.AffiliateOffer> priced = d.offers().stream().filter(o -> o.displayPriceMinor() != null).toList();
            Map<String, Object> offer = new LinkedHashMap<>();
            if (priced.size() > 1) {
                offer.put("@type", "AggregateOffer");
                offer.put("lowPrice", decimal(priced.get(0).displayPriceMinor()));
                offer.put("highPrice", decimal(priced.stream().mapToLong(CatalogPublicService.AffiliateOffer::displayPriceMinor).max().orElseThrow()));
                offer.put("offerCount", priced.size());
            } else {
                offer.put("@type", "Offer");
                offer.put("price", decimal(affiliatePrice));
                offer.put("seller", Map.of("@type", "Organization", "name", d.affiliate().merchantName()));
            }
            offer.put("priceCurrency", "INR");
            offer.put("availability", "https://schema.org/InStock");
            offer.put("url", abs(path));
            ld.put("offers", offer);
        }
        if (rating.count() > 0) {
            ld.put("aggregateRating", Map.of("@type", "AggregateRating", "ratingValue", rating.average(),
                    "reviewCount", rating.count(), "bestRating", 5, "worstRating", 1));
            List<Map<String, Object>> rv = new ArrayList<>();
            for (var r : reviews.latestApproved(entity.getId(), 5)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("@type", "Review");
                m.put("author", Map.of("@type", "Person", "name", r.authorName()));
                m.put("reviewRating", Map.of("@type", "Rating", "ratingValue", r.rating(), "bestRating", 5));
                if (r.title() != null) m.put("name", r.title());
                if (r.body() != null) m.put("reviewBody", r.body());
                m.put("datePublished", r.createdAt().toString().substring(0, 10));
                rv.add(m);
            }
            ld.put("review", rv);
        }
        p.jsonLd(ld);
        // Product videos: Google shows them in video results only with a thumbnail, so fall back to the product image.
        for (var v : d.videos()) {
            String poster = v.posterUrl() != null ? abs(v.posterUrl()) : image;
            if (poster == null) continue;
            Map<String, Object> video = new LinkedHashMap<>();
            video.put("@context", "https://schema.org");
            video.put("@type", "VideoObject");
            video.put("name", v.title());
            video.put("description", description);
            video.put("thumbnailUrl", List.of(poster));
            video.put("uploadDate", v.uploadedAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
            video.put("contentUrl", abs(v.url()));
            if (v.durationSeconds() != null) video.put("duration", java.time.Duration.ofSeconds(v.durationSeconds()).toString());
            p.jsonLd(video);
        }
        p.jsonLd(breadcrumbLd(sf, s, trail));
        return Optional.of(p);
    }

    // ------------------------------------------------------------------ content pages

    @Transactional
    public Optional<SeoPage> contentPage(Storefront sf, String slug) {
        Optional<ContentPage> found = content.published(sf, slug);
        if (found.isEmpty()) return Optional.empty();
        ContentPage c = found.get();
        StoreSettings s = settings.entity(sf);
        String path = ContentService.url(c);
        String description = c.getMetaDescription() != null ? c.getMetaDescription()
                : truncate(ContentMarkup.toPlainText(c.getBody()), 160);
        String body = "<div class=\"container narrow-content\">" + breadcrumb(sf, s, List.of(Map.entry(c.getTitle(), path)))
                + "<article class=\"card content-page\"><h1>" + esc(c.getTitle()) + "</h1><div class=\"content-body\">"
                + ContentMarkup.toHtml(c.getBody()) + "</div></article></div>";
        SeoPage p = base(sf, s)
                .title(c.getMetaTitle() != null ? c.getMetaTitle() : c.getTitle() + " | " + s.getStoreName())
                .description(description).canonical(abs(path)).ogType("article").body(body);
        p.jsonLd(breadcrumbLd(sf, s, List.of(Map.entry(c.getTitle(), path))));
        return Optional.of(p);
    }

    // ------------------------------------------------------------------ coupons & store pages

    /** /{sf}/offers: every live coupon code and deal, grouped by store. */
    @Transactional
    public SeoPage offersPage(Storefront sf) {
        StoreSettings s = settings.entity(sf);
        String path = "/" + sf.path() + "/offers";
        List<OfferService.PublicOffer> live = offers.live(sf, null, null);
        Map<String, List<OfferService.PublicOffer>> byStore = new LinkedHashMap<>();
        live.forEach(o -> byStore.computeIfAbsent(o.merchantName(), k -> new ArrayList<>()).add(o));
        List<String> names = new ArrayList<>(byStore.keySet());
        String stores = names.size() <= 1 ? String.join("", names)
                : names.size() <= 5 ? String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1)
                : String.join(", ", names.subList(0, 5)) + " and more";
        String description = live.isEmpty()
                ? "Coupon codes and deals from our partner stores at " + s.getStoreName() + "."
                : (live.size() == 1 ? "1 working coupon or deal" : live.size() + " working coupon codes and deals")
                + " from " + stores + ", checked by " + s.getStoreName() + ".";
        StringBuilder body = new StringBuilder("<div class=\"container\">");
        body.append(breadcrumb(sf, s, List.of(Map.entry("Coupons & offers", path))));
        body.append("<header class=\"page-title\"><h1>Coupons &amp; offers.</h1><p class=\"muted\">")
                .append(esc(description)).append("</p></header>");
        if (live.isEmpty()) {
            body.append("<div class=\"card empty\">No offers right now. Check back soon.</div>");
        }
        for (var e : byStore.entrySet()) {
            String domain = e.getValue().get(0).merchantDomain();
            body.append("<section class=\"offer-store\"><div class=\"section-head\"><h2>").append(esc(e.getKey()))
                    .append(". <span class=\"sub\">").append(e.getValue().size()).append(e.getValue().size() == 1 ? " offer" : " offers")
                    .append("</span></h2><a class=\"see-all\" href=\"").append(esc("/" + sf.path() + "/store/" + domain))
                    .append("\">All ").append(esc(e.getKey())).append(" deals</a></div><div class=\"offer-grid\">");
            e.getValue().forEach(o -> body.append(offerCard(o)));
            body.append("</div></section>");
        }
        body.append("</div>");
        SeoPage p = base(sf, s).title("Coupons & offers | " + s.getStoreName()).description(description).canonical(abs(path))
                .noindex(live.isEmpty()).body(body.toString());
        p.jsonLd(breadcrumbLd(sf, s, List.of(Map.entry("Coupons & offers", path))));
        return p;
    }

    /** One offer: title, details, expiry, and a button that copies the code and opens the store (see store.js). */
    static String offerCard(OfferService.PublicOffer o) {
        StringBuilder b = new StringBuilder("<article class=\"offer-card\"><div class=\"offer-store-name\">")
                .append(esc(o.merchantName())).append("</div><h3>").append(esc(o.title())).append("</h3>");
        if (o.description() != null) b.append("<p class=\"muted\">").append(esc(o.description())).append("</p>");
        b.append("<div class=\"offer-meta\">");
        if (o.verifiedAt() != null) b.append("<span class=\"badge green\">Verified</span> ");
        if (o.endsAt() != null) {
            b.append("<span class=\"muted small\">Ends ").append(esc(o.endsAt().atZone(java.time.ZoneId.of("Asia/Kolkata"))
                    .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)))).append("</span>");
        }
        b.append("</div>");
        if (o.code() != null) {
            b.append("<button class=\"btn offer-cta\" type=\"button\" data-offer-go=\"").append(esc(o.goUrl())).append("\" data-offer-code=\"")
                    .append(esc(o.code())).append("\">Copy code &amp; go to store</button>");
        } else {
            b.append("<a class=\"btn offer-cta\" href=\"").append(esc(o.goUrl()))
                    .append("\" target=\"_blank\" rel=\"sponsored nofollow noopener\">Get this deal</a>");
        }
        return b.append("</article>").toString();
    }

    /** /{sf}/store/{domain}: a partner store's deals and coupons (the page itself is drawn by store.js). */
    @Transactional
    public Optional<SeoPage> storePage(Storefront sf, String domain) {
        Optional<CatalogPublicService.StoreView> store = catalog.stores(sf).stream().filter(st -> st.domain().equalsIgnoreCase(domain)).findFirst();
        if (store.isEmpty()) return Optional.empty();
        StoreSettings s = settings.entity(sf);
        String name = store.get().name();
        String path = "/" + sf.path() + "/store/" + store.get().domain();
        List<OfferService.PublicOffer> live = offers.live(sf, store.get().domain(), null);
        PageResponse<CatalogPublicService.ProductCard> list = catalog.browse(sf, null, null, null, null, null, store.get().domain(),
                null, null, null, 0, 24);
        String description = name + " deals" + (live.isEmpty() ? "" : " and " + live.size() + (live.size() == 1 ? " coupon" : " coupons"))
                + " hand-picked by " + s.getStoreName() + ". Compare prices before you buy.";
        StringBuilder body = new StringBuilder("<div class=\"container ssr\">");
        body.append(breadcrumb(sf, s, List.of(Map.entry(name, path))));
        body.append("<h1>").append(esc(name)).append(" deals &amp; coupons</h1><p class=\"muted\">").append(esc(description)).append("</p>");
        if (!live.isEmpty()) {
            body.append("<div class=\"offer-grid\">");
            live.forEach(o -> body.append(offerCard(o)));
            body.append("</div>");
        }
        body.append(grid(sf, list.items()));
        body.append("</div>");
        SeoPage p = base(sf, s).title(name + " deals & coupons | " + s.getStoreName()).description(description).canonical(abs(path))
                .noindex(list.items().isEmpty() && live.isEmpty()).body(body.toString());
        p.jsonLd(breadcrumbLd(sf, s, List.of(Map.entry(name, path))));
        if (!list.items().isEmpty()) p.jsonLd(itemList(sf, list.items()));
        return Optional.of(p);
    }

    // ------------------------------------------------------------------ buying guides

    /** /{sf}/guides: every published guide, newest first. */
    @Transactional
    public SeoPage guidesIndex(Storefront sf) {
        StoreSettings s = settings.entity(sf);
        String path = "/" + sf.path() + "/guides";
        List<GuideService.GuideCard> list = guides.cards(sf, null, 50);
        String description = "Honest buying guides from " + s.getStoreName() + ": what to buy, what to skip and the best prices in India right now.";
        StringBuilder body = new StringBuilder("<div class=\"container\">");
        body.append(breadcrumb(sf, s, List.of(Map.entry("Buying guides", path))));
        body.append("<header class=\"page-title\"><h1>Buying guides.</h1><p class=\"muted\">").append(esc(description)).append("</p></header>");
        if (list.isEmpty()) body.append("<div class=\"card empty\">No guides yet. Check back soon.</div>");
        else body.append(guideGrid(list));
        body.append("</div>");
        SeoPage p = base(sf, s).title("Buying guides | " + s.getStoreName()).description(description).canonical(abs(path))
                .noindex(list.isEmpty()).body(body.toString());
        p.jsonLd(breadcrumbLd(sf, s, List.of(Map.entry("Buying guides", path))));
        return p;
    }

    /** /{sf}/guides/{slug}: the article with its product cards. */
    @Transactional
    public Optional<SeoPage> guide(Storefront sf, String slug) {
        return guides.published(sf, slug).map(g -> guidePage(sf, g, false));
    }

    /** The article page; {@code preview} also shows drafts and flags products that are not live. */
    @Transactional
    public SeoPage guidePage(Storefront sf, Guide g, boolean preview) {
        StoreSettings s = settings.entity(sf);
        String path = g.path();
        GuideService.Rendered r = guides.render(sf, g.getBody(), preview);
        String description = g.getMetaDescription() != null ? g.getMetaDescription() : truncate(GuideService.summaryOf(g), 160);
        Instant updated = g.getUpdatedAt();
        String date = (updated == null ? Instant.now() : updated).atZone(java.time.ZoneId.of("Asia/Kolkata"))
                .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH));
        String cover = r.products().stream().map(CatalogPublicService.ProductCard::imageUrl).filter(Objects::nonNull).findFirst().orElse(null);
        List<Map.Entry<String, String>> trail = List.of(Map.entry("Buying guides", "/" + sf.path() + "/guides"), Map.entry(g.getTitle(), path));

        StringBuilder body = new StringBuilder("<div class=\"container narrow-content guide-page\">");
        if (preview) {
            body.append("<p class=\"note\">Preview").append(g.isPublished() ? "" : " of a draft — shoppers cannot see this guide yet").append(".</p>");
        }
        body.append(breadcrumb(sf, s, trail));
        body.append("<article class=\"guide-article\"><header class=\"guide-head\"><p class=\"eyebrow\">Buying guide</p><h1>")
                .append(esc(g.getTitle())).append("</h1>");
        if (g.getSummary() != null) body.append("<p class=\"guide-summary\">").append(esc(g.getSummary())).append("</p>");
        body.append("<p class=\"guide-meta muted small\">");
        if (g.getAuthorName() != null) body.append("By ").append(esc(g.getAuthorName())).append(" · ");
        body.append("Updated ").append(esc(date)).append(" · ").append(r.readMinutes()).append(" min read</p></header>");
        body.append("<p class=\"guide-disclosure small muted\">We may earn a commission when you buy through links in this guide, at no extra cost to you. "
                + "Prices and offers are set by the stores and can change.</p>");
        if (r.toc().size() >= 3) {
            body.append("<nav class=\"guide-toc\" aria-label=\"In this guide\"><strong>In this guide</strong><ol>");
            r.toc().forEach(t -> body.append("<li><a href=\"#").append(t[0]).append("\">").append(t[1]).append("</a></li>"));
            body.append("</ol></nav>");
        }
        body.append("<div class=\"content-body guide-body\">").append(r.html()).append("</div></article>");
        List<GuideService.GuideCard> more = guides.cards(sf, null, 4).stream().filter(c -> !c.slug().equals(g.getSlug())).limit(3).toList();
        if (!more.isEmpty()) body.append("<section class=\"guide-more\"><h2>More buying guides</h2>").append(guideGrid(more)).append("</section>");
        body.append("</div>");

        SeoPage p = base(sf, s).title(g.getMetaTitle() != null ? g.getMetaTitle() : g.getTitle() + " | " + s.getStoreName())
                .description(description).canonical(abs(path)).ogType("article").noindex(preview || !g.isPublished())
                .image(cover == null ? abs(LOGO.formatted(sf == Storefront.SHOP ? "elaashop" : "elaatech")) : abs(cover))
                .body(body.toString());
        Map<String, Object> article = new LinkedHashMap<>();
        article.put("@context", "https://schema.org");
        article.put("@type", "Article");
        article.put("headline", g.getTitle().length() > 110 ? g.getTitle().substring(0, 110) : g.getTitle());
        article.put("description", description);
        if (cover != null) article.put("image", List.of(abs(cover)));
        if (g.getPublishedAt() != null) article.put("datePublished", g.getPublishedAt().toString());
        if (updated != null) article.put("dateModified", updated.toString());
        article.put("author", g.getAuthorName() != null ? Map.of("@type", "Person", "name", g.getAuthorName())
                : Map.of("@type", "Organization", "name", s.getStoreName(), "url", abs("/" + sf.path())));
        article.put("publisher", Map.of("@type", "Organization", "name", s.getStoreName(),
                "logo", Map.of("@type", "ImageObject", "url", abs(LOGO.formatted(sf == Storefront.SHOP ? "elaashop" : "elaatech")))));
        article.put("mainEntityOfPage", abs(path));
        p.jsonLd(article);
        p.jsonLd(breadcrumbLd(sf, s, trail));
        if (!r.products().isEmpty()) p.jsonLd(itemList(sf, r.products()));
        return p;
    }

    private static String guideGrid(List<GuideService.GuideCard> list) {
        StringBuilder sb = new StringBuilder("<div class=\"guide-grid\">");
        for (var c : list) {
            sb.append("<a class=\"guide-card\" href=\"").append(esc(c.url())).append("\"><div class=\"guide-cover\">");
            if (c.coverUrl() != null) sb.append("<img src=\"").append(esc(c.coverUrl())).append("\" alt=\"\" loading=\"lazy\" decoding=\"async\">");
            sb.append("</div><div class=\"guide-card-body\"><span class=\"eyebrow\">Buying guide · ").append(c.readMinutes())
                    .append(" min read</span><h3>").append(esc(c.title())).append("</h3>");
            if (c.summary() != null) sb.append("<p class=\"muted small\">").append(esc(truncate(c.summary(), 140))).append("</p>");
            sb.append("</div></a>");
        }
        return sb.append("</div>").toString();
    }

    // ------------------------------------------------------------------ web stories

    /** /{sf}/stories: every published story as a tappable portrait card. */
    @Transactional
    public SeoPage storiesIndex(Storefront sf) {
        StoreSettings s = settings.entity(sf);
        String path = "/" + sf.path() + "/stories";
        List<WebStoryService.StoryCard> cards = stories.cards(sf, 50);
        String description = "Quick, swipeable stories from " + s.getStoreName() + ": hand-picked deals, buying tips and price drops.";
        StringBuilder body = new StringBuilder("<div class=\"container\">");
        body.append(breadcrumb(sf, s, List.of(Map.entry("Web Stories", path))));
        body.append("<div class=\"section-head\"><h1>Web Stories</h1></div><p class=\"muted\">").append(esc(description)).append("</p>");
        if (cards.isEmpty()) {
            body.append("<div class=\"card empty\">No stories yet. Check back soon.</div>");
        } else {
            body.append("<div class=\"story-grid\">");
            for (var w : cards) {
                body.append("<a class=\"story-card\" href=\"").append(esc(w.url())).append("\">");
                if (w.coverUrl() != null) {
                    body.append("<img src=\"").append(esc(w.coverUrl())).append("\" alt=\"\" width=\"720\" height=\"960\" loading=\"lazy\" decoding=\"async\">");
                }
                body.append("<span class=\"story-title\">").append(esc(w.title())).append("</span></a>");
            }
            body.append("</div>");
        }
        body.append("</div>");
        SeoPage p = base(sf, s).title("Web Stories | " + s.getStoreName()).description(description).canonical(abs(path))
                .noindex(cards.isEmpty()).body(body.toString());
        if (!cards.isEmpty()) {
            Map<String, Object> list = new LinkedHashMap<>();
            list.put("@context", "https://schema.org");
            list.put("@type", "ItemList");
            List<Map<String, Object>> items = new ArrayList<>();
            int i = 1;
            for (var w : cards) items.add(Map.of("@type", "ListItem", "position", i++, "url", abs(w.url()), "name", w.title()));
            list.put("itemListElement", items);
            p.jsonLd(list);
        }
        p.jsonLd(breadcrumbLd(sf, s, List.of(Map.entry("Web Stories", path))));
        return p;
    }

    // ------------------------------------------------------------------ 404

    @Transactional
    public SeoPage notFound(Storefront sf) {
        StoreSettings s = settings.entity(sf);
        return base(sf, s).title("Page not found | " + s.getStoreName()).noindex(true)
                .body("<div class=\"container\"><div class=\"card empty\"><h1>Page not found</h1>"
                        + "<p>This page does not exist or is no longer available.</p>"
                        + "<p><a class=\"btn\" href=\"/" + sf.path() + "\">Back to " + esc(s.getStoreName()) + "</a></p></div></div>");
    }

    // ------------------------------------------------------------------ helpers

    private SeoPage base(Storefront sf, StoreSettings s) {
        return new SeoPage().siteName(s.getStoreName())
                .meta("google-site-verification", s.getGoogleVerification())
                .meta("msvalidate.01", s.getBingVerification());
    }

    static String productDescription(Product p) {
        if (p.getMetaDescription() != null) return p.getMetaDescription();
        if (p.getSummary() != null && p.getSummary().length() >= 50) return truncate(p.getSummary(), 160);
        String text = ((p.getSummary() == null ? "" : p.getSummary() + " ") + (p.getDescription() == null ? "" : p.getDescription())).trim();
        return text.isEmpty() ? p.getTitle() : truncate(text.replaceAll("\\s+", " "), 160);
    }

    private static void appendList(StringBuilder body, String heading, String cssClass, List<String> items) {
        if (items == null || items.isEmpty()) return;
        body.append("<h2>").append(heading).append("</h2><ul class=\"").append(cssClass).append("\">");
        items.forEach(i -> body.append("<li>").append(esc(i)).append("</li>"));
        body.append("</ul>");
    }

    static String truncate(String text, int max) {
        if (text == null) return null;
        String t = text.replaceAll("\\s+", " ").trim();
        if (t.length() <= max) return t;
        int cut = t.lastIndexOf(' ', max - 1);
        return (cut > max / 2 ? t.substring(0, cut) : t.substring(0, max - 1)).replaceAll("[\\s,.;:-]+$", "") + "\u2026";
    }

    private String grid(Storefront sf, List<CatalogPublicService.ProductCard> items) {
        if (items.isEmpty()) return "<div class=\"empty card\">No products found.</div>";
        StringBuilder sb = new StringBuilder("<div class=\"grid\">");
        for (var p : items) {
            sb.append("<a class=\"product-card\" href=\"").append(esc("/" + sf.path() + "/p/" + p.slug())).append("\">")
                    .append("<div class=\"thumb\">");
            if (p.imageUrl() != null) {
                sb.append("<img src=\"").append(esc(p.imageUrl())).append("\" alt=\"").append(esc(p.title())).append("\" loading=\"lazy\">");
            }
            sb.append("</div><div class=\"body\"><div class=\"title\">").append(esc(p.title())).append("</div>");
            if (p.summary() != null) sb.append("<div class=\"muted small\">").append(esc(p.summary())).append("</div>");
            sb.append("<div class=\"spacer\"></div>");
            if (p.priceMinor() != null) sb.append("<div class=\"price\">").append(esc(money(p.priceMinor()))).append("</div>");
            sb.append("</div></a>");
        }
        return sb.append("</div>").toString();
    }

    private static String pager(String path, int page, int totalPages) {
        if (totalPages <= 1) return "";
        StringBuilder sb = new StringBuilder("<div class=\"pager\">");
        if (page > 0) sb.append("<a class=\"btn secondary small\" rel=\"prev\" href=\"").append(esc(path + (page - 1 > 0 ? "?page=" + (page - 1) : ""))).append("\">Previous</a>");
        sb.append("<span class=\"muted small\">Page ").append(page + 1).append(" of ").append(totalPages).append("</span>");
        if (page + 1 < totalPages) sb.append("<a class=\"btn secondary small\" rel=\"next\" href=\"").append(esc(path + "?page=" + (page + 1))).append("\">Next</a>");
        return sb.append("</div>").toString();
    }

    private String breadcrumb(Storefront sf, StoreSettings s, List<Map.Entry<String, String>> trail) {
        StringBuilder sb = new StringBuilder("<nav class=\"breadcrumb small\" aria-label=\"Breadcrumb\"><a href=\"/")
                .append(sf.path()).append("\">").append(esc(s.getStoreName())).append("</a>");
        for (int i = 0; i < trail.size(); i++) {
            sb.append(" <span aria-hidden=\"true\">/</span> ");
            var e = trail.get(i);
            if (i == trail.size() - 1) sb.append("<span>").append(esc(e.getKey())).append("</span>");
            else sb.append("<a href=\"").append(esc(e.getValue())).append("\">").append(esc(e.getKey())).append("</a>");
        }
        return sb.append("</nav>").toString();
    }

    private Map<String, Object> breadcrumbLd(Storefront sf, StoreSettings s, List<Map.Entry<String, String>> trail) {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(Map.of("@type", "ListItem", "position", 1, "name", s.getStoreName(), "item", abs("/" + sf.path())));
        int pos = 2;
        for (var e : trail) {
            items.add(Map.of("@type", "ListItem", "position", pos++, "name", e.getKey(), "item", abs(e.getValue())));
        }
        Map<String, Object> ld = new LinkedHashMap<>();
        ld.put("@context", "https://schema.org");
        ld.put("@type", "BreadcrumbList");
        ld.put("itemListElement", items);
        return ld;
    }

    private Map<String, Object> itemList(Storefront sf, List<CatalogPublicService.ProductCard> items) {
        List<Map<String, Object>> list = new ArrayList<>();
        int pos = 1;
        for (var p : items) {
            list.add(Map.of("@type", "ListItem", "position", pos++, "url", abs("/" + sf.path() + "/p/" + p.slug()), "name", p.title()));
        }
        Map<String, Object> ld = new LinkedHashMap<>();
        ld.put("@context", "https://schema.org");
        ld.put("@type", "ItemList");
        ld.put("itemListElement", list);
        return ld;
    }

    private static int pageParam(Map<String, String> query) {
        try {
            return Math.max(0, Math.min(Integer.parseInt(query.getOrDefault("page", "0")), 10_000));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String decimal(long minor) {
        return BigDecimal.valueOf(minor, 2).toPlainString();
    }

    static String money(long minor) {
        NumberFormat f = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"));
        f.setCurrency(Currency.getInstance("INR"));
        return f.format(BigDecimal.valueOf(minor, 2));
    }

    private static String stars(double avg) {
        int full = (int) Math.round(avg);
        return "<span class=\"stars\" aria-label=\"" + avg + " out of 5 stars\">" + "\u2605".repeat(full) + "\u2606".repeat(5 - full) + "</span>";
    }
}
