package com.hybrid.platform.guides;

import com.hybrid.platform.catalog.CatalogPublicService;
import com.hybrid.platform.catalog.ProductRepository;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.audit.AuditService;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.content.ContentMarkup;
import com.hybrid.platform.security.AdminPrincipal;
import com.hybrid.platform.seo.SeoRedirectService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.hybrid.platform.seo.SeoPage.esc;

/**
 * Buying guides and blog articles. The body uses the content-page text format (headings, lists, notes, links,
 * comparison tables) plus product cards: a line {@code [product: slug]} shows one product with its price and
 * buy button, a line {@code [products: slug-1, slug-2, slug-3]} shows a row of top picks.
 */
@Service
public class GuideService {

    static final int MAX_BODY = 60_000;
    static final int MAX_PRODUCTS = 30;
    static final int MIN_PUBLISH_CHARS = 150;
    private static final Pattern EMBED = Pattern.compile("^\\[(products?)\\s*:\\s*([^\\]]{1,800})]$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern H2 = Pattern.compile("<h2>(.*?)</h2>");
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");

    public record GuideInput(String title, String slug, String summary, String body, String authorName,
                             String metaTitle, String metaDescription, Boolean publish) {
    }

    public record AdminGuideRow(Long id, String title, String slug, String status, Instant publishedAt, Instant updatedAt,
                                int productCount, String url) {
    }

    public record AdminGuideView(Long id, String title, String slug, String summary, String body, String authorName,
                                 String metaTitle, String metaDescription, String status, Instant publishedAt,
                                 Instant updatedAt, String url, int productCount, int readMinutes) {
    }

    public record GuideCard(String slug, String title, String summary, String coverUrl, String authorName,
                            Instant publishedAt, Instant updatedAt, int readMinutes, String url) {
    }

    /** Article HTML, its section headings (id, escaped text) and the live products shown in it. */
    public record Rendered(String html, List<String[]> toc, List<CatalogPublicService.ProductCard> products,
                           int readMinutes, String plainText) {
    }

    private final GuideRepository guides;
    private final ProductRepository products;
    private final CatalogPublicService catalog;
    private final SeoRedirectService redirects;
    private final AuditService audit;

    public GuideService(GuideRepository guides, ProductRepository products, CatalogPublicService catalog,
                        SeoRedirectService redirects, AuditService audit) {
        this.guides = guides;
        this.products = products;
        this.catalog = catalog;
        this.redirects = redirects;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ admin

    @Transactional(readOnly = true)
    public List<AdminGuideRow> list(Storefront sf) {
        return guides.findByStorefrontOrderByUpdatedAtDesc(sf).stream()
                .map(g -> new AdminGuideRow(g.getId(), g.getTitle(), g.getSlug(), g.getStatus().name(), g.getPublishedAt(),
                        g.getUpdatedAt(), g.getProductIds().size(), g.path()))
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminGuideView get(Storefront sf, Long id) {
        return view(find(sf, id));
    }

    @Transactional(readOnly = true)
    public Guide entity(Storefront sf, Long id) {
        Guide g = find(sf, id);
        g.getProductIds().size();
        return g;
    }

    @Transactional
    public AdminGuideView create(AdminPrincipal admin, GuideInput in) {
        Guide g = new Guide(admin.storefront());
        apply(g, in, true);
        guides.save(g);
        audit.record(admin, g.isPublished() ? "GUIDE_PUBLISHED" : "GUIDE_CREATED", "GUIDE", g.getId(), g.getTitle());
        return view(g);
    }

    @Transactional
    public AdminGuideView update(AdminPrincipal admin, Long id, GuideInput in) {
        Guide g = find(admin.storefront(), id);
        boolean wasPublished = g.isPublished();
        String oldPath = g.path();
        apply(g, in, false);
        g.touch();
        if (wasPublished && !oldPath.equals(g.path())) redirects.autoRedirect(g.getStorefront(), oldPath, g.path());
        String action = !wasPublished && g.isPublished() ? "GUIDE_PUBLISHED" : wasPublished && !g.isPublished() ? "GUIDE_UNPUBLISHED" : "GUIDE_UPDATED";
        audit.record(admin, action, "GUIDE", g.getId(), g.getTitle());
        return view(g);
    }

    @Transactional
    public void delete(AdminPrincipal admin, Long id) {
        Guide g = find(admin.storefront(), id);
        guides.delete(g);
        audit.record(admin, "GUIDE_DELETED", "GUIDE", id, g.getTitle());
    }

    private Guide find(Storefront sf, Long id) {
        return guides.findByIdAndStorefront(id, sf).orElseThrow(() -> ApiException.notFound("GUIDE_NOT_FOUND", "Guide not found"));
    }

    private void apply(Guide g, GuideInput in, boolean creating) {
        if (in.title() != null || creating) {
            String title = trim(in.title());
            if (title == null || title.length() < 3) throw ApiException.badRequest("GUIDE_TITLE_REQUIRED", "Give the guide a title (at least 3 characters)");
            if (title.length() > 200) throw ApiException.badRequest("GUIDE_TITLE_TOO_LONG", "Keep the title under 200 characters");
            g.setTitle(title);
        }
        if (in.slug() != null || creating) {
            String slug = trim(in.slug());
            slug = slug == null ? slugify(g.getTitle()) : slug.toLowerCase(Locale.ROOT);
            if (slug.length() > 140 || !SLUG.matcher(slug).matches()) {
                throw ApiException.badRequest("INVALID_GUIDE_SLUG", "Use only small letters, numbers and dashes in the web address, e.g. best-earbuds-under-2000");
            }
            String finalSlug = slug;
            guides.findByStorefrontAndSlug(g.getStorefront(), slug).filter(o -> !o.getId().equals(g.getId())).ifPresent(o -> {
                throw ApiException.conflict("GUIDE_SLUG_TAKEN", "Another guide already uses the address " + finalSlug);
            });
            g.setSlug(slug);
        }
        if (in.summary() != null) g.setSummary(limit(in.summary(), 400, "Summary"));
        if (in.authorName() != null) g.setAuthorName(limit(in.authorName(), 120, "Author name"));
        if (in.metaTitle() != null) g.setMetaTitle(limit(in.metaTitle(), 200, "Search title"));
        if (in.metaDescription() != null) g.setMetaDescription(limit(in.metaDescription(), 320, "Search description"));
        if (in.body() != null || creating) {
            String body = in.body() == null ? "" : in.body().replace("\r\n", "\n");
            if (body.length() > MAX_BODY) throw ApiException.badRequest("GUIDE_TOO_LONG", "The guide is too long (max " + MAX_BODY + " characters)");
            List<String> slugs = embeddedSlugs(body);
            if (slugs.size() > MAX_PRODUCTS) throw ApiException.badRequest("TOO_MANY_PRODUCTS", "Show at most " + MAX_PRODUCTS + " products in one guide");
            Map<String, Long> ids = new HashMap<>();
            if (!slugs.isEmpty()) products.findByStorefrontAndSlugIn(g.getStorefront(), slugs).forEach(p -> ids.put(p.getSlug(), p.getId()));
            for (String s : slugs) {
                if (!ids.containsKey(s)) {
                    throw ApiException.badRequest("GUIDE_PRODUCT_NOT_FOUND", "No product has the web address “" + s + "”. Use “Insert product” to pick one.");
                }
            }
            g.setBody(body);
            g.setProductIds(slugs.stream().map(ids::get).toList());
        }
        if (Boolean.TRUE.equals(in.publish())) {
            if (plainText(g.getBody()).length() < MIN_PUBLISH_CHARS) {
                throw ApiException.badRequest("GUIDE_TOO_SHORT", "Write at least a few sentences (" + MIN_PUBLISH_CHARS + "+ characters) before publishing");
            }
            g.publish();
        } else if (Boolean.FALSE.equals(in.publish())) {
            g.unpublish();
        }
    }

    private AdminGuideView view(Guide g) {
        return new AdminGuideView(g.getId(), g.getTitle(), g.getSlug(), g.getSummary(), g.getBody(), g.getAuthorName(),
                g.getMetaTitle(), g.getMetaDescription(), g.getStatus().name(), g.getPublishedAt(), g.getUpdatedAt(), g.path(),
                g.getProductIds().size(), readMinutes(plainText(g.getBody())));
    }

    // ------------------------------------------------------------------ public

    @Transactional(readOnly = true)
    public Optional<Guide> published(Storefront sf, String slug) {
        return guides.findByStorefrontAndSlug(sf, slug).filter(Guide::isPublished).map(g -> {
            g.getProductIds().size();
            return g;
        });
    }

    @Transactional(readOnly = true)
    public List<GuideCard> cards(Storefront sf, Long productId, int limit) {
        List<Guide> list = productId == null
                ? guides.findByStorefrontAndStatusOrderByPublishedAtDesc(sf, Guide.Status.PUBLISHED)
                : guides.findFeaturing(sf, Guide.Status.PUBLISHED, productId);
        return toCards(sf, list.stream().limit(Math.max(1, Math.min(limit, 50))).toList());
    }

    @Transactional(readOnly = true)
    public List<Guide> allPublished() {
        List<Guide> list = guides.findByStatus(Guide.Status.PUBLISHED);
        list.forEach(g -> g.getProductIds().size());
        return list;
    }

    List<GuideCard> toCards(Storefront sf, List<Guide> list) {
        List<Long> firstIds = list.stream().filter(g -> !g.getProductIds().isEmpty()).map(g -> g.getProductIds().get(0)).distinct().toList();
        Map<Long, String> images = new HashMap<>();
        catalog.cards(sf, firstIds).forEach(c -> images.put(c.id(), c.imageUrl()));
        return list.stream().map(g -> new GuideCard(g.getSlug(), g.getTitle(), summaryOf(g),
                g.getProductIds().isEmpty() ? null : images.get(g.getProductIds().get(0)), g.getAuthorName(),
                g.getPublishedAt(), g.getUpdatedAt(), readMinutes(plainText(g.getBody())), g.path())).toList();
    }

    public static String summaryOf(Guide g) {
        if (g.getSummary() != null) return g.getSummary();
        String text = plainText(g.getBody());
        return text.length() <= 200 ? text : text.substring(0, text.lastIndexOf(' ', 197) > 100 ? text.lastIndexOf(' ', 197) : 197) + "…";
    }

    // ------------------------------------------------------------------ rendering

    /** Turns the body into article HTML; products that are not live are left out (or flagged in a preview). */
    @Transactional(readOnly = true)
    public Rendered render(Storefront sf, String body, boolean preview) {
        String text = body == null ? "" : body;
        List<String> slugs = embeddedSlugs(text);
        Map<String, CatalogPublicService.ProductCard> cards = new HashMap<>();
        if (!slugs.isEmpty()) {
            Map<Long, String> slugById = new HashMap<>();
            products.findByStorefrontAndSlugIn(sf, slugs).forEach(p -> slugById.put(p.getId(), p.getSlug()));
            catalog.cards(sf, new ArrayList<>(slugById.keySet())).forEach(c -> cards.put(slugById.get(c.id()), c));
        }
        StringBuilder html = new StringBuilder();
        StringBuilder block = new StringBuilder();
        LinkedHashMap<Long, CatalogPublicService.ProductCard> shown = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            Matcher m = EMBED.matcher(line.strip());
            if (!m.matches()) {
                block.append(line).append('\n');
                continue;
            }
            html.append(ContentMarkup.toHtml(block.toString()));
            block.setLength(0);
            List<String> wanted = splitSlugs(m.group(2));
            List<CatalogPublicService.ProductCard> live = wanted.stream().map(cards::get).filter(Objects::nonNull).toList();
            live.forEach(c -> shown.putIfAbsent(c.id(), c));
            if (preview) {
                wanted.stream().filter(s -> !cards.containsKey(s)).forEach(s -> html.append("<p class=\"note\">Product “")
                        .append(esc(s)).append("” is not live, so shoppers will not see it here.</p>"));
            }
            if (live.isEmpty()) continue;
            if (m.group(1).equalsIgnoreCase("product") || live.size() == 1) live.forEach(c -> html.append(productCard(sf, c)));
            else html.append(picks(sf, live));
        }
        html.append(ContentMarkup.toHtml(block.toString()));

        // anchors for the "In this guide" box
        List<String[]> toc = new ArrayList<>();
        Set<String> used = new HashSet<>();
        Matcher h = H2.matcher(html);
        StringBuilder withIds = new StringBuilder();
        while (h.find()) {
            String label = TAGS.matcher(h.group(1)).replaceAll("").strip();
            String id = "s-" + slugify(label.replaceAll("&[a-z]+;|&#\\d+;", " "));
            String unique = id;
            for (int i = 2; !used.add(unique); i++) unique = id + "-" + i;
            toc.add(new String[]{unique, label});
            h.appendReplacement(withIds, Matcher.quoteReplacement("<h2 id=\"" + unique + "\">" + h.group(1) + "</h2>"));
        }
        h.appendTail(withIds);
        String plain = plainText(text);
        return new Rendered(withIds.toString(), toc, new ArrayList<>(shown.values()), readMinutes(plain), plain);
    }

    private static String productCard(Storefront sf, CatalogPublicService.ProductCard c) {
        String page = "/" + sf.path() + "/p/" + c.slug();
        StringBuilder b = new StringBuilder("<div class=\"guide-product\"><a class=\"gp-media\" href=\"").append(esc(page)).append("\">");
        if (c.imageUrl() != null) b.append("<img src=\"").append(esc(c.imageUrl())).append("\" alt=\"").append(esc(c.title())).append("\" loading=\"lazy\" decoding=\"async\">");
        b.append("</a><div class=\"gp-body\">");
        if (c.merchantName() != null) b.append("<div class=\"gp-store\">").append(esc(c.merchantName())).append("</div>");
        b.append("<h3 class=\"gp-title\"><a href=\"").append(esc(page)).append("\">").append(esc(c.title())).append("</a></h3>");
        if (c.summary() != null) b.append("<p class=\"muted\">").append(esc(c.summary())).append("</p>");
        b.append(price(c)).append("<div class=\"gp-cta\">").append(buyButton(sf, c, "btn"))
                .append("<a class=\"link-more\" href=\"").append(esc(page)).append("\">Full details</a></div></div></div>");
        return b.toString();
    }

    private static String picks(Storefront sf, List<CatalogPublicService.ProductCard> list) {
        StringBuilder b = new StringBuilder("<div class=\"guide-picks\">");
        for (var c : list) {
            String page = "/" + sf.path() + "/p/" + c.slug();
            b.append("<div class=\"gp-pick\"><a class=\"gp-media\" href=\"").append(esc(page)).append("\">");
            if (c.imageUrl() != null) b.append("<img src=\"").append(esc(c.imageUrl())).append("\" alt=\"").append(esc(c.title())).append("\" loading=\"lazy\" decoding=\"async\">");
            b.append("</a><h3 class=\"gp-title\"><a href=\"").append(esc(page)).append("\">").append(esc(c.title())).append("</a></h3>")
                    .append(price(c)).append(buyButton(sf, c, "btn small")).append("</div>");
        }
        return b.append("</div>").toString();
    }

    private static String price(CatalogPublicService.ProductCard c) {
        if (c.priceMinor() == null) {
            return c.merchantName() == null ? "" : "<div class=\"gp-price muted small\">See today’s price at " + esc(c.merchantName()) + "</div>";
        }
        StringBuilder b = new StringBuilder("<div class=\"gp-price\"><strong>").append(esc(money(c.priceMinor()))).append("</strong>");
        if (c.compareAtMinor() != null && c.compareAtMinor() > c.priceMinor()) {
            b.append(" <s class=\"muted\">").append(esc(money(c.compareAtMinor()))).append("</s>");
        }
        if (c.discountPercent() != null && c.discountPercent() > 0) b.append(" <span class=\"gp-off\">").append(c.discountPercent()).append("% off</span>");
        return b.append("</div>").toString();
    }

    private static String buyButton(Storefront sf, CatalogPublicService.ProductCard c, String cls) {
        if (c.goUrl() == null) {
            return "<a class=\"" + cls + "\" href=\"" + esc("/" + sf.path() + "/p/" + c.slug()) + "\">View product</a>";
        }
        String label = c.merchantName() != null && c.merchantName().length() <= 16 ? "Buy on " + c.merchantName() : "Check price";
        return "<a class=\"" + cls + "\" href=\"" + esc(c.goUrl()) + "\" target=\"_blank\" rel=\"sponsored nofollow noopener\">" + esc(label) + "</a>";
    }

    // ------------------------------------------------------------------ helpers

    static List<String> embeddedSlugs(String body) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (body == null) return List.of();
        for (String line : body.split("\n")) {
            Matcher m = EMBED.matcher(line.strip());
            if (m.matches()) out.addAll(splitSlugs(m.group(2)));
        }
        return new ArrayList<>(out);
    }

    private static List<String> splitSlugs(String raw) {
        List<String> out = new ArrayList<>();
        for (String s : raw.split(",")) {
            String t = s.strip().toLowerCase(Locale.ROOT);
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    /** Readable text without markup or product lines (meta description, reading time). */
    public static String plainText(String body) {
        if (body == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : body.split("\n")) if (!EMBED.matcher(line.strip()).matches()) sb.append(line).append('\n');
        return ContentMarkup.toPlainText(sb.toString());
    }

    static int readMinutes(String plain) {
        int words = plain.isBlank() ? 0 : plain.trim().split("\\s+").length;
        return Math.max(1, (int) Math.ceil(words / 200.0));
    }

    static String slugify(String text) {
        String s = (text == null ? "" : text).toLowerCase(Locale.ROOT).replace("₹", "rs ").replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (s.length() > 120) s = s.substring(0, 120).replaceAll("-+$", "");
        return s.isEmpty() ? "guide" : s;
    }

    private static String trim(String v) {
        if (v == null) return null;
        String t = v.strip();
        return t.isEmpty() ? null : t;
    }

    private static String limit(String v, int max, String label) {
        String t = trim(v);
        if (t != null && t.length() > max) throw ApiException.badRequest("FIELD_TOO_LONG", label + " is too long (max " + max + " characters)");
        return t;
    }

    static String money(long minor) {
        NumberFormat f = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"));
        f.setCurrency(Currency.getInstance("INR"));
        return f.format(BigDecimal.valueOf(minor, 2));
    }
}
