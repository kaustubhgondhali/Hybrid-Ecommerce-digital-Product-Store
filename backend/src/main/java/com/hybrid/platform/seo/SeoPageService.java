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
import com.hybrid.platform.review.ReviewService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.NumberFormat;
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
    private final AppProperties props;

    public SeoPageService(SettingsService settings, ProductRepository products, CategoryRepository categories,
                          CatalogPublicService catalog, ContentService content, ReviewService reviews,
                          AppProperties props) {
        this.settings = settings;
        this.products = products;
        this.categories = categories;
        this.catalog = catalog;
        this.content = content;
        this.reviews = reviews;
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
        String image = d.images().isEmpty() ? null : abs(d.images().get(0).url());
        ReviewService.Rating rating = reviews.rating(entity.getId());

        Long low = null, high = null;
        boolean anyAvailable = false;
        for (var v : d.variants()) {
            if (v.priceMinor() == null) continue;
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
            body.append("<img src=\"").append(esc(img.url())).append("\" alt=\"").append(esc(img.alt())).append("\">");
        }
        body.append("</div></div><div>");
        body.append("<h1>").append(esc(d.title())).append("</h1>");
        if (d.brand() != null) body.append("<p class=\"muted\">by ").append(esc(d.brand())).append("</p>");
        if (rating.count() > 0) {
            body.append("<p class=\"rating-line\">").append(stars(rating.average())).append(' ')
                    .append(rating.average()).append(" / 5 (").append(rating.count()).append(rating.count() == 1 ? " review" : " reviews").append(")</p>");
        }
        Long shown = low != null ? low : affiliatePrice;
        if (shown != null) body.append("<p class=\"price\">").append(esc(money(shown))).append("</p>");
        if (d.summary() != null) body.append("<p>").append(esc(d.summary())).append("</p>");
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
            Map<String, Object> offer = new LinkedHashMap<>();
            offer.put("@type", "Offer");
            offer.put("price", decimal(affiliatePrice));
            offer.put("priceCurrency", "INR");
            offer.put("availability", "https://schema.org/InStock");
            offer.put("url", abs(path));
            offer.put("seller", Map.of("@type", "Organization", "name", d.affiliate().merchantName()));
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
