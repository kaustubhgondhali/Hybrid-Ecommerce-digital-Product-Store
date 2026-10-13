package com.hybrid.platform.seo;

import com.hybrid.platform.affiliate.AffiliateService;
import com.hybrid.platform.catalog.CatalogEnums.ProductStatus;
import com.hybrid.platform.catalog.CatalogEnums.SellingMethod;
import com.hybrid.platform.catalog.Category;
import com.hybrid.platform.catalog.CategoryRepository;
import com.hybrid.platform.catalog.Product;
import com.hybrid.platform.catalog.ProductImage;
import com.hybrid.platform.catalog.ProductRepository;
import com.hybrid.platform.catalog.ProductVariant;
import com.hybrid.platform.commerce.settings.SettingsRepositories.StoreSettingsRepository;
import com.hybrid.platform.commerce.settings.SettingsService;
import com.hybrid.platform.commerce.settings.StoreSettings;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.audit.AuditService;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.content.ContentPage;
import com.hybrid.platform.content.ContentService;
import com.hybrid.platform.review.ReviewService;
import com.hybrid.platform.security.AdminPrincipal;
import com.hybrid.platform.seo.SeoAnalyzer.Status;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Admin SEO tool: per-product analysis, site-wide audit, SEO settings. */
@Service
public class SeoAdminService {

    private static final Pattern VERIFICATION = Pattern.compile("^[A-Za-z0-9_\\-=+/.]{1,120}$");
    private static final Pattern META_CONTENT = Pattern.compile("content\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROBOTS_LINE = Pattern.compile("^(#.*|\\s*|(User-agent|Disallow|Allow|Crawl-delay|Sitemap)\\s*:\\s*\\S.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> REQUIRED_PAGES = Set.of("privacy-policy", "terms-and-conditions", "refund-policy", "contact-us");

    public record ProductRow(Long id, String title, String slug, String status, int score, String grade, long problems,
                             String topIssue) {
    }

    public record ProductSeo(Long id, String title, String slug, String status, String metaTitle, String metaDescription,
                             String focusKeyword, boolean noindex, String url, List<ImageAlt> images,
                             SeoAnalyzer.Analysis analysis) {
    }

    public record ImageAlt(Long id, String url, String alt) {
    }

    public record DraftInput(String title, String slug, String metaTitle, String metaDescription, String focusKeyword,
                             String summary, String description, Boolean noindex) {
    }

    public record SiteCheck(String id, String label, Status status, String message) {
    }

    public record Overview(int siteScore, int averageProductScore, int productsAnalyzed, int productsNeedingWork,
                           long redirects, long notFoundUrls, int sitemapUrls, List<SiteCheck> checks,
                           List<ProductRow> worstProducts) {
    }

    public record SettingsView(String seoTitle, String seoDescription, String googleVerification,
                               String bingVerification, String robotsExtra, String baseUrl, String sitemapUrl,
                               String robotsUrl, SeoAnalyzer.Serp homePreview) {
    }

    public record SettingsInput(String seoTitle, String seoDescription, String googleVerification,
                                String bingVerification, String robotsExtra) {
    }

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final SettingsService settings;
    private final StoreSettingsRepository settingsRepo;
    private final AffiliateService affiliate;
    private final ReviewService reviews;
    private final ContentService content;
    private final SeoRedirectService redirects;
    private final SitemapService sitemap;
    private final SeoPageService pages;
    private final AuditService audit;

    public SeoAdminService(ProductRepository products, CategoryRepository categories, SettingsService settings,
                           StoreSettingsRepository settingsRepo, AffiliateService affiliate, ReviewService reviews,
                           ContentService content, SeoRedirectService redirects, SitemapService sitemap,
                           SeoPageService pages, AuditService audit) {
        this.products = products;
        this.categories = categories;
        this.settings = settings;
        this.settingsRepo = settingsRepo;
        this.affiliate = affiliate;
        this.reviews = reviews;
        this.content = content;
        this.redirects = redirects;
        this.sitemap = sitemap;
        this.pages = pages;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ products

    @Transactional
    public List<ProductRow> productRows(Storefront sf) {
        Context ctx = context(sf);
        return ctx.all.stream().map(p -> {
            var a = analyze(ctx, p, null);
            String top = a.checks().stream().filter(c -> c.status() == Status.FAIL).findFirst()
                    .or(() -> a.checks().stream().filter(c -> c.status() == Status.WARN).max(Comparator.comparingInt(SeoAnalyzer.Check::weight)))
                    .map(SeoAnalyzer.Check::label).orElse(null);
            return new ProductRow(p.getId(), p.getTitle(), p.getSlug(), p.getStatus().name(), a.score(), a.grade(), a.problems(), top);
        }).sorted(Comparator.comparingInt(ProductRow::score)).toList();
    }

    @Transactional
    public ProductSeo product(Storefront sf, Long id, DraftInput draft) {
        Context ctx = context(sf);
        Product p = ctx.all.stream().filter(x -> x.getId().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("PRODUCT_NOT_FOUND", "Product not found"));
        var analysis = analyze(ctx, p, draft);
        String slug = draft != null && draft.slug() != null && !draft.slug().isBlank() ? draft.slug() : p.getSlug();
        return new ProductSeo(p.getId(), p.getTitle(), p.getSlug(), p.getStatus().name(), p.getMetaTitle(),
                p.getMetaDescription(), p.getFocusKeyword(), p.isNoindex(), pages.abs("/" + sf.path() + "/p/" + slug),
                p.getImages().stream().map(i -> new ImageAlt(i.getId(), i.thumbUrl(), i.getAltText())).toList(), analysis);
    }

    private SeoAnalyzer.Analysis analyze(Context ctx, Product p, DraftInput d) {
        String title = pick(d == null ? null : d.title(), p.getTitle());
        String metaTitle = d != null && d.metaTitle() != null ? blankToNull(d.metaTitle()) : p.getMetaTitle();
        String metaDescription = d != null && d.metaDescription() != null ? blankToNull(d.metaDescription()) : p.getMetaDescription();
        String keyword = d != null && d.focusKeyword() != null ? blankToNull(d.focusKeyword()) : p.getFocusKeyword();
        String slug = pick(d == null ? null : d.slug(), p.getSlug());
        String summary = d != null && d.summary() != null ? blankToNull(d.summary()) : p.getSummary();
        String description = d != null && d.description() != null ? blankToNull(d.description()) : p.getDescription();
        boolean noindex = d != null && d.noindex() != null ? d.noindex() : p.isNoindex();

        String effectiveTitle = metaTitle != null ? metaTitle : title + " | " + ctx.storeName;
        String auto = autoDescription(summary, description, title);
        String descForDup = metaDescription != null ? metaDescription : auto;
        boolean dupTitle = othersWith(ctx.titleCounts, key(effectiveTitle), key(ctx.effectiveTitle(p))) > 0;
        boolean dupDesc = othersWith(ctx.descCounts, key(descForDup), key(ctx.effectiveDescription(p))) > 0;

        boolean hasPrice = p.isOnSite()
                ? p.activeVariants().stream().map(ProductVariant::getPriceMinor).anyMatch(Objects::nonNull)
                : ctx.affiliatePrices.contains(p.getId());
        int missingAlt = (int) p.getImages().stream().map(ProductImage::getAltText).filter(a -> a == null || a.isBlank()).count();

        return SeoAnalyzer.analyze(new SeoAnalyzer.Input(effectiveTitle, metaDescription, auto, keyword, slug,
                pages.abs("/" + p.getStorefront().path() + "/p/" + slug), summary, description,
                p.getImages().size(), missingAlt, p.getCategoryId() != null, p.getBrand() != null, hasPrice,
                ctx.ratings.getOrDefault(p.getId(), ReviewService.Rating.NONE).count(), noindex, dupTitle, dupDesc,
                p.getStatus() == ProductStatus.PUBLISHED));
    }

    // ------------------------------------------------------------------ site audit

    @Transactional
    public Overview overview(Storefront sf) {
        StoreSettings s = settings.entity(sf);
        List<ProductRow> rows = productRows(sf);
        List<ProductRow> published = rows.stream().filter(r -> r.status().equals("PUBLISHED")).toList();
        int avg = published.isEmpty() ? 0 : (int) Math.round(published.stream().mapToInt(ProductRow::score).average().orElse(0));
        List<SiteCheck> checks = new ArrayList<>();

        String base = pages.baseUrl();
        if (base.startsWith("https://") && !base.contains("localhost")) {
            checks.add(new SiteCheck("base-url", "Site address", Status.PASS, "Canonical URLs and the sitemap use " + base + "."));
        } else {
            checks.add(new SiteCheck("base-url", "Site address", Status.FAIL,
                    "APP_BASE_URL is " + base + ". Set it to your real https:// domain before launch; canonical URLs, sitemap and social previews use it."));
        }
        int tl = s.getSeoTitle() == null ? 0 : s.getSeoTitle().length();
        checks.add(s.getSeoTitle() == null
                ? new SiteCheck("home-title", "Home page title", Status.WARN, "Using the default title. Write your own 30-60 character home page title.")
                : new SiteCheck("home-title", "Home page title", tl >= 30 && tl <= 60 ? Status.PASS : Status.WARN, "Home page title is " + tl + " characters (ideal 30-60)."));
        int dl = s.getSeoDescription() == null ? 0 : s.getSeoDescription().length();
        checks.add(s.getSeoDescription() == null
                ? new SiteCheck("home-description", "Home page description", Status.WARN, "Using the default description. Write your own 120-160 character description.")
                : new SiteCheck("home-description", "Home page description", dl >= 120 && dl <= 160 ? Status.PASS : Status.WARN, "Home page description is " + dl + " characters (ideal 120-160)."));
        checks.add(s.getGoogleVerification() != null
                ? new SiteCheck("google", "Google Search Console", Status.PASS, "Verification tag is set. Submit " + pages.abs("/sitemap.xml") + " in Search Console.")
                : new SiteCheck("google", "Google Search Console", Status.WARN, "Add your Google Search Console verification code, then submit the sitemap."));
        checks.add(s.getBingVerification() != null
                ? new SiteCheck("bing", "Bing Webmaster Tools", Status.PASS, "Verification tag is set.")
                : new SiteCheck("bing", "Bing Webmaster Tools", Status.WARN, "Optional: add a Bing Webmaster Tools verification code."));
        checks.add(s.getSellerLegalName() != null && s.getSupportEmail() != null
                ? new SiteCheck("organization", "Business details", Status.PASS, "Business name and support email are published as Organization data.")
                : new SiteCheck("organization", "Business details", Status.WARN, "Fill in the legal business name and support email in Settings; they build trust and appear in structured data."));

        Map<String, ContentPage> bySlug = content.allPublished(sf).stream().collect(Collectors.toMap(ContentPage::getSlug, c -> c, (a, b) -> a));
        List<String> missing = REQUIRED_PAGES.stream().filter(slug -> !bySlug.containsKey(slug)).sorted().toList();
        checks.add(missing.isEmpty()
                ? new SiteCheck("legal-pages", "Policy pages", Status.PASS, "Privacy, terms, refund and contact pages are published.")
                : new SiteCheck("legal-pages", "Policy pages", Status.FAIL, "Missing or unpublished: " + String.join(", ", missing) + "."));
        long templates = bySlug.values().stream().filter(c -> c.getBody().contains("> Template:") || c.getBody().matches("(?s).*\\[[^\\]]{2,60}].*")).count();
        checks.add(templates == 0
                ? new SiteCheck("page-placeholders", "Page placeholders", Status.PASS, "No template placeholders left on published pages.")
                : new SiteCheck("page-placeholders", "Page placeholders", Status.WARN, templates + " published page(s) still contain [placeholders] or the template note. Edit them under Pages."));

        List<Category> cats = categories.findByStorefrontAndActiveTrueOrderBySortOrderAscNameAsc(sf);
        long noDesc = cats.stream().filter(c -> c.getDescription() == null).count();
        if (!cats.isEmpty()) {
            checks.add(noDesc == 0
                    ? new SiteCheck("category-content", "Category descriptions", Status.PASS, "All categories have descriptions.")
                    : new SiteCheck("category-content", "Category descriptions", Status.WARN, noDesc + " of " + cats.size() + " categories have no description. Category pages rank better with a short intro."));
        }
        long poor = published.stream().filter(r -> r.score() < 50).count();
        if (!published.isEmpty()) {
            checks.add(new SiteCheck("product-scores", "Product SEO", poor == 0 ? (avg >= 80 ? Status.PASS : Status.WARN) : Status.FAIL,
                    "Average product score " + avg + "/100; " + poor + " published product(s) score below 50."));
        } else {
            checks.add(new SiteCheck("product-scores", "Product SEO", Status.WARN, "No published products yet."));
        }
        long notFound = redirects.notFoundCount(sf);
        checks.add(notFound == 0
                ? new SiteCheck("not-found", "Broken links (404)", Status.PASS, "No broken storefront URLs recorded.")
                : new SiteCheck("not-found", "Broken links (404)", Status.WARN, notFound + " URL(s) returned \u201Cnot found\u201D. Redirect the important ones under 404 monitor."));

        String prefix = pages.abs("/" + sf.path());
        int sitemapUrls = (int) sitemap.entries().stream().filter(e -> e.url().startsWith(prefix)).count();

        double total = 0, got = 0;
        for (SiteCheck c : checks) {
            total += 1;
            got += c.status() == Status.PASS ? 1 : c.status() == Status.WARN ? 0.5 : 0;
        }
        int siteScore = (int) Math.round((got / total * 100) * 0.5 + (published.isEmpty() ? 0 : avg) * 0.5);
        return new Overview(siteScore, avg, rows.size(), (int) rows.stream().filter(r -> r.score() < 80).count(),
                redirects.list(sf).size(), notFound, sitemapUrls, checks, rows.stream().limit(10).toList());
    }

    // ------------------------------------------------------------------ settings

    @Transactional
    public SettingsView settings(Storefront sf) {
        StoreSettings s = settings.entity(sf);
        String title = s.getSeoTitle() != null ? s.getSeoTitle() : s.getStoreName() + " | " + SeoPageService.DEFAULTS.get(sf)[0];
        String desc = s.getSeoDescription() != null ? s.getSeoDescription() : SeoPageService.DEFAULTS.get(sf)[1];
        return new SettingsView(s.getSeoTitle(), s.getSeoDescription(), s.getGoogleVerification(), s.getBingVerification(),
                s.getRobotsExtra(), pages.baseUrl(), pages.abs("/sitemap.xml"), pages.abs("/robots.txt"),
                SeoAnalyzer.serp(title, pages.abs("/" + sf.path()), desc));
    }

    @Transactional
    public SettingsView updateSettings(AdminPrincipal admin, SettingsInput in) {
        StoreSettings s = settings.entity(admin.storefront());
        if (in.seoTitle() != null) s.setSeoTitle(limit(in.seoTitle(), 200));
        if (in.seoDescription() != null) s.setSeoDescription(limit(in.seoDescription(), 320));
        if (in.googleVerification() != null) s.setGoogleVerification(verification(in.googleVerification()));
        if (in.bingVerification() != null) s.setBingVerification(verification(in.bingVerification()));
        if (in.robotsExtra() != null) {
            String r = in.robotsExtra().strip();
            if (r.length() > 2000) throw ApiException.badRequest("ROBOTS_TOO_LONG", "Extra robots.txt rules must be at most 2000 characters");
            for (String line : r.split("\\R")) {
                if (!ROBOTS_LINE.matcher(line).matches()) {
                    throw ApiException.badRequest("INVALID_ROBOTS_RULE", "Invalid robots.txt line: " + (line.length() > 60 ? line.substring(0, 60) : line));
                }
            }
            s.setRobotsExtra(r.isEmpty() ? null : r);
        }
        settingsRepo.save(s);
        audit.record(admin, "SEO_SETTINGS_UPDATED", "StoreSettings", admin.storefront().name(), null);
        return settings(admin.storefront());
    }

    /** Accepts either the bare code or the whole &lt;meta ... content="code"&gt; tag pasted from Google/Bing. */
    static String verification(String raw) {
        String v = raw.trim();
        if (v.isEmpty()) return null;
        Matcher m = META_CONTENT.matcher(v);
        if (m.find()) v = m.group(1).trim();
        if (!VERIFICATION.matcher(v).matches()) {
            throw ApiException.badRequest("INVALID_VERIFICATION", "Paste the verification code (or the full meta tag) from Google/Bing");
        }
        return v;
    }

    // ------------------------------------------------------------------ helpers

    private final class Context {
        final List<Product> all;
        final String storeName;
        final Map<String, Integer> titleCounts = new HashMap<>();
        final Map<String, Integer> descCounts = new HashMap<>();
        final Set<Long> affiliatePrices = new HashSet<>();
        final Map<Long, ReviewService.Rating> ratings;

        Context(Storefront sf) {
            storeName = settings.entity(sf).getStoreName();
            all = products.findByStorefrontAndStatusNotOrderByUpdatedAtDesc(sf, ProductStatus.ARCHIVED);
            for (Product p : all) {
                titleCounts.merge(key(effectiveTitle(p)), 1, Integer::sum);
                descCounts.merge(key(effectiveDescription(p)), 1, Integer::sum);
            }
            List<Long> affIds = all.stream().filter(p -> p.getSellingMethod() == SellingMethod.AFFILIATE).map(Product::getId).toList();
            affiliate.offers(affIds).forEach((id, offer) -> { if (offer.displayPriceMinor() != null) affiliatePrices.add(id); });
            ratings = reviews.ratings(all.stream().map(Product::getId).toList());
        }

        String effectiveTitle(Product p) {
            return p.getMetaTitle() != null ? p.getMetaTitle() : p.getTitle() + " | " + storeName;
        }

        String effectiveDescription(Product p) {
            return p.getMetaDescription() != null ? p.getMetaDescription() : autoDescription(p.getSummary(), p.getDescription(), p.getTitle());
        }
    }

    private Context context(Storefront sf) {
        return new Context(sf);
    }

    private static String autoDescription(String summary, String description, String title) {
        if (summary != null && summary.length() >= 50) return SeoPageService.truncate(summary, 160);
        String text = ((summary == null ? "" : summary + " ") + (description == null ? "" : description)).trim();
        return text.isEmpty() ? title : SeoPageService.truncate(text, 160);
    }

    /** How many other products use {@code value}, given that this product currently counts towards {@code own}. */
    private static int othersWith(Map<String, Integer> counts, String value, String own) {
        return counts.getOrDefault(value, 0) - (value.equals(own) ? 1 : 0);
    }

    private static String key(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String pick(String draft, String current) {
        return draft != null && !draft.isBlank() ? draft.trim() : current;
    }

    private static String blankToNull(String v) {
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static String limit(String v, int max) {
        String t = v.trim();
        if (t.length() > max) throw ApiException.badRequest("VALUE_TOO_LONG", "Value must be at most " + max + " characters");
        return t.isEmpty() ? null : t;
    }
}
