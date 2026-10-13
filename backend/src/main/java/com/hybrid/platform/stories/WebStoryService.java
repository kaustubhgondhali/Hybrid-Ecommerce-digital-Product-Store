package com.hybrid.platform.stories;

import com.hybrid.platform.catalog.CatalogAdminService;
import com.hybrid.platform.catalog.CatalogEnums.ProductStatus;
import com.hybrid.platform.catalog.CatalogPublicService;
import com.hybrid.platform.catalog.Product;
import com.hybrid.platform.catalog.ProductImage;
import com.hybrid.platform.catalog.ProductRepository;
import com.hybrid.platform.commerce.settings.SettingsService;
import com.hybrid.platform.common.AppProperties;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.audit.AuditService;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.security.AdminPrincipal;
import com.hybrid.platform.seo.SeoRedirectService;
import com.hybrid.platform.storage.FileStorage;
import com.hybrid.platform.storage.FileTypes;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.*;

/**
 * Web Stories: the admin builds a story from photos and short videos (or from products in one step), publishes it, and
 * it appears at the bottom of the storefront, in the sitemap and as a Google Web Story.
 */
@Service
public class WebStoryService {

    static final int MIN_PAGES = 3;
    static final int MAX_PAGES = 30;
    static final int MAX_VIDEO_SECONDS = 60;
    static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    static final long MAX_VIDEO_BYTES = 30L * 1024 * 1024;
    static final int TITLE_TIP = 70;

    /** Card on the storefront strip and the /stories page. */
    public record StoryCard(String title, String slug, String url, String coverUrl, Instant publishedAt, int pages) {
    }

    public record AdminStorySummary(Long id, String title, String slug, String status, String coverUrl, int pages,
                                    Instant updatedAt, Instant publishedAt, String url) {
    }

    public record AdminPageView(Long id, String mediaType, String mediaUrl, String posterUrl, Integer width, Integer height,
                                String altText, String headline, String body, Long productId, String productTitle,
                                String ctaLabel) {
    }

    public record AdminStoryView(Long id, String title, String slug, String description, String status, String coverUrl,
                                 Instant publishedAt, Instant updatedAt, String url, List<AdminPageView> pages,
                                 List<String> problems, List<String> tips) {
    }

    public record StoryInput(String title, String slug, String description) {
    }

    public record PageInput(String headline, String body, Long productId, String ctaLabel, String altText) {
    }

    public record FromProductsInput(String title, String description, List<Long> productIds, Boolean publish) {
    }

    /** A rendered story page plus whether search engines may index it. */
    public record Rendered(String html, boolean preview) {
    }

    private final WebStoryRepository stories;
    private final ProductRepository products;
    private final CatalogPublicService catalog;
    private final StoryImages images;
    private final FileStorage storage;
    private final WebStoryRenderer renderer;
    private final SettingsService settings;
    private final SeoRedirectService redirects;
    private final AuditService audit;
    private final AppProperties props;

    public WebStoryService(WebStoryRepository stories, ProductRepository products, CatalogPublicService catalog,
                           StoryImages images, FileStorage storage, WebStoryRenderer renderer, SettingsService settings,
                           SeoRedirectService redirects, AuditService audit, AppProperties props) {
        this.stories = stories;
        this.products = products;
        this.catalog = catalog;
        this.images = images;
        this.storage = storage;
        this.renderer = renderer;
        this.settings = settings;
        this.redirects = redirects;
        this.audit = audit;
        this.props = props;
    }

    // ------------------------------------------------------------------ admin: stories

    @Transactional(readOnly = true)
    public List<AdminStorySummary> list(Storefront sf) {
        return stories.findByStorefrontOrderByUpdatedAtDesc(sf).stream().map(s -> new AdminStorySummary(s.getId(), s.getTitle(),
                s.getSlug(), s.getStatus().name(), s.coverUrl(), s.getPages().size(), s.getUpdatedAt(), s.getPublishedAt(), s.path())).toList();
    }

    @Transactional(readOnly = true)
    public AdminStoryView get(Storefront sf, Long id) {
        return view(require(sf, id));
    }

    @Transactional
    public AdminStoryView create(AdminPrincipal admin, StoryInput in) {
        String title = title(in == null ? null : in.title());
        WebStory s = new WebStory(admin.storefront(), uniqueSlug(admin.storefront(), in.slug() != null && !in.slug().isBlank() ? in.slug() : title, null), title);
        s.setDescription(clean(in.description(), 300, "Description"));
        stories.save(s);
        audit.record(admin, "STORY_CREATED", "WebStory", s.getId(), title);
        return view(s);
    }

    @Transactional
    public AdminStoryView update(AdminPrincipal admin, Long id, StoryInput in) {
        WebStory s = require(admin.storefront(), id);
        if (in.title() != null) s.setTitle(title(in.title()));
        if (in.description() != null) s.setDescription(clean(in.description(), 300, "Description"));
        if (in.slug() != null && !in.slug().isBlank()) {
            String slug = uniqueSlug(admin.storefront(), in.slug(), s.getId());
            if (!slug.equals(s.getSlug())) {
                String old = s.path();
                s.setSlug(slug);
                if (s.isPublished()) redirects.autoRedirect(admin.storefront(), old, s.path());
            }
        }
        s.changed();
        audit.record(admin, "STORY_UPDATED", "WebStory", id, null);
        return view(s);
    }

    /** Saves the cover as a 720 x 960 portrait (Google's poster format) whatever the shape of the photo. */
    @Transactional
    public AdminStoryView uploadCover(AdminPrincipal admin, Long id, MultipartFile file) throws IOException {
        WebStory s = require(admin.storefront(), id);
        BufferedImage img = readImage(file, "cover");
        String old = s.getCoverKey();
        s.setCoverKey(images.storeCover(img));
        s.changed();
        if (old != null) storage.delete(FileStorage.Area.PUBLIC, old);
        audit.record(admin, "STORY_COVER", "WebStory", id, null);
        return view(s);
    }

    @Transactional
    public AdminStoryView publish(AdminPrincipal admin, Long id) {
        WebStory s = require(admin.storefront(), id);
        List<String> problems = problems(s);
        if (!problems.isEmpty()) {
            throw ApiException.rule("STORY_NOT_READY", "Before publishing: " + String.join("; ", problems));
        }
        s.publish();
        audit.record(admin, "STORY_PUBLISHED", "WebStory", id, s.getTitle());
        return view(s);
    }

    @Transactional
    public AdminStoryView unpublish(AdminPrincipal admin, Long id) {
        WebStory s = require(admin.storefront(), id);
        s.unpublish();
        audit.record(admin, "STORY_UNPUBLISHED", "WebStory", id, null);
        return view(s);
    }

    @Transactional
    public void delete(AdminPrincipal admin, Long id) {
        WebStory s = require(admin.storefront(), id);
        List<String> keys = new ArrayList<>();
        if (s.getCoverKey() != null) keys.add(s.getCoverKey());
        s.getPages().forEach(p -> keys.addAll(mediaKeys(p)));
        stories.delete(s);
        keys.forEach(k -> storage.delete(FileStorage.Area.PUBLIC, k));
        audit.record(admin, "STORY_DELETED", "WebStory", id, s.getTitle());
    }

    // ------------------------------------------------------------------ admin: pages

    /**
     * Adds a page with a photo (JPEG, PNG, WebP or GIF, max 5 MB) or a video (MP4 or WebM, max 30 MB and 60 seconds,
     * with the poster frame captured by the admin's browser). Content is checked, not the file name.
     */
    @Transactional
    public AdminStoryView addPage(AdminPrincipal admin, Long id, MultipartFile media, MultipartFile poster, PageInput in,
                                  Integer durationSeconds, Integer width, Integer height) throws IOException {
        WebStory s = require(admin.storefront(), id);
        if (s.getPages().size() >= MAX_PAGES) {
            throw ApiException.rule("TOO_MANY_PAGES", "A story can have at most " + MAX_PAGES + " pages");
        }
        if (media == null || media.isEmpty()) throw ApiException.badRequest("FILE_REQUIRED", "Choose a photo or video for this page");
        WebStoryPage page;
        try (InputStream in2 = media.getInputStream()) {
            byte[] header = in2.readNBytes(16);
            String imageExt = imageExtension(header);
            InputStream all = new SequenceInputStream(new ByteArrayInputStream(header), in2);
            if (imageExt != null) {
                if (media.getSize() > MAX_IMAGE_BYTES) throw ApiException.rule("FILE_TOO_LARGE", "Photos must be 5 MB or smaller");
                FileStorage.StoredFile stored = storage.store(FileStorage.Area.PUBLIC, all, imageExt, MAX_IMAGE_BYTES);
                Integer w = within(width, 1, 20_000);
                Integer h = within(height, 1, 20_000);
                if (w == null || h == null) {
                    BufferedImage img = images.read(stored.key());
                    if (img != null) {
                        w = img.getWidth();
                        h = img.getHeight();
                    }
                }
                page = new WebStoryPage(WebStoryPage.MediaType.IMAGE, stored.key(), null, w, h);
            } else {
                String ext = FileTypes.requireVideo(header);
                if (media.getSize() > MAX_VIDEO_BYTES) throw ApiException.rule("FILE_TOO_LARGE", "Story videos must be 30 MB or smaller");
                if (durationSeconds != null && durationSeconds > MAX_VIDEO_SECONDS) {
                    throw ApiException.rule("VIDEO_TOO_LONG", "Story videos must be " + MAX_VIDEO_SECONDS + " seconds or shorter (15 seconds works best)");
                }
                if (poster == null || poster.isEmpty()) {
                    throw ApiException.rule("POSTER_REQUIRED", "This video could not be read in your browser. Export it as MP4 (H.264) and try again");
                }
                String posterKey = storeImage(poster);
                try {
                    FileStorage.StoredFile stored = storage.store(FileStorage.Area.PUBLIC, all, ext, MAX_VIDEO_BYTES);
                    page = new WebStoryPage(WebStoryPage.MediaType.VIDEO, stored.key(), posterKey, within(width, 1, 20_000), within(height, 1, 20_000));
                } catch (IOException | RuntimeException e) {
                    storage.delete(FileStorage.Area.PUBLIC, posterKey);
                    throw e;
                }
            }
        }
        apply(admin.storefront(), page, in == null ? new PageInput(null, null, null, null, null) : in);
        s.addPage(page);
        stories.saveAndFlush(s);
        audit.record(admin, "STORY_PAGE_ADDED", "WebStory", id, page.getMediaType().name());
        return view(s);
    }

    @Transactional
    public AdminStoryView updatePage(AdminPrincipal admin, Long id, Long pageId, PageInput in) {
        WebStory s = require(admin.storefront(), id);
        apply(admin.storefront(), page(s, pageId), in);
        s.changed();
        return view(s);
    }

    /** Moves a page one place earlier ({@code direction} -1) or later (+1). */
    @Transactional
    public AdminStoryView movePage(AdminPrincipal admin, Long id, Long pageId, int direction) {
        WebStory s = require(admin.storefront(), id);
        List<WebStoryPage> pages = s.getPages();
        WebStoryPage p = page(s, pageId);
        int from = pages.indexOf(p);
        int to = from + (direction < 0 ? -1 : 1);
        if (to >= 0 && to < pages.size()) {
            Collections.swap(pages, from, to);
            for (int i = 0; i < pages.size(); i++) pages.get(i).setSortOrder(i);
            s.changed();
        }
        return view(s);
    }

    @Transactional
    public AdminStoryView deletePage(AdminPrincipal admin, Long id, Long pageId) {
        WebStory s = require(admin.storefront(), id);
        WebStoryPage p = page(s, pageId);
        List<String> keys = mediaKeys(p);
        s.getPages().remove(p);
        s.changed();
        if (s.isPublished() && s.getPages().size() < MIN_PAGES) s.unpublish();
        stories.saveAndFlush(s);
        keys.forEach(k -> storage.delete(FileStorage.Area.PUBLIC, k));
        audit.record(admin, "STORY_PAGE_REMOVED", "WebStory", id, null);
        return view(s);
    }

    /**
     * Builds a story from 3 to 10 published products in one step: the first product's photo becomes the cover, then one
     * page per product with its photo, name and a highlight, linking to the product page. Prices are added when the
     * story is shown, so they are always current.
     */
    @Transactional
    public AdminStoryView fromProducts(AdminPrincipal admin, FromProductsInput in) throws IOException {
        Storefront sf = admin.storefront();
        String title = title(in == null ? null : in.title());
        List<Long> ids = in.productIds() == null ? List.of() : in.productIds().stream().filter(Objects::nonNull).distinct().toList();
        if (ids.size() < MIN_PAGES || ids.size() > 10) {
            throw ApiException.badRequest("CHOOSE_PRODUCTS", "Choose between " + MIN_PAGES + " and 10 products");
        }
        Map<Long, Product> byId = new HashMap<>();
        products.findAllById(ids).forEach(p -> byId.put(p.getId(), p));
        List<Product> chosen = new ArrayList<>();
        for (Long pid : ids) {
            Product p = byId.get(pid);
            if (p == null || p.getStorefront() != sf) throw ApiException.notFound("PRODUCT_NOT_FOUND", "Product not found");
            if (p.getStatus() != ProductStatus.PUBLISHED) {
                throw ApiException.rule("PRODUCT_NOT_PUBLISHED", "“" + p.getTitle() + "” is not published yet");
            }
            if (p.getImages().isEmpty()) throw ApiException.rule("PRODUCT_HAS_NO_IMAGE", "“" + p.getTitle() + "” has no photo");
            chosen.add(p);
        }
        WebStory s = new WebStory(sf, uniqueSlug(sf, title, null), title);
        String description = clean(in.description(), 300, "Description");
        if (description == null) {
            description = "Hand-picked: " + String.join(", ", chosen.stream().limit(4).map(Product::getTitle).toList());
            if (description.length() > 300) description = description.substring(0, 297).strip() + "…";
        }
        s.setDescription(description);
        List<String> created = new ArrayList<>();
        try {
            for (Product p : chosen) {
                ProductImage img = p.getImages().get(0);
                String source = img.getLargeKey() != null ? img.getLargeKey() : img.getStorageKey();
                if (s.getCoverKey() == null) {
                    BufferedImage decoded = images.read(source);
                    if (decoded != null) {
                        s.setCoverKey(images.storeCover(decoded));
                        created.add(s.getCoverKey());
                    }
                }
                FileStorage.StoredFile copy = images.copy(source, MAX_IMAGE_BYTES * 2);
                created.add(copy.key());
                Integer w = img.getWidth();
                Integer h = img.getHeight();
                if (w != null && h != null && img.getLargeKey() != null && w > 1200) {
                    h = Math.round(h * (1200f / w));
                    w = 1200;
                }
                WebStoryPage page = new WebStoryPage(WebStoryPage.MediaType.IMAGE, copy.key(), null, w, h);
                page.setHeadline(cut(p.getTitle(), 90));
                page.setBody(cut(firstLine(p.getHighlights()) != null ? firstLine(p.getHighlights()) : p.getSummary(), 250));
                page.setAltText(cut(img.getAltText() != null ? img.getAltText() : p.getTitle(), 200));
                page.setProductId(p.getId());
                page.setCtaLabel("See the deal");
                s.addPage(page);
            }
        } catch (IOException | RuntimeException e) {
            created.forEach(k -> storage.delete(FileStorage.Area.PUBLIC, k));
            throw e;
        }
        stories.save(s);
        if (Boolean.TRUE.equals(in.publish()) && problems(s).isEmpty()) s.publish();
        audit.record(admin, "STORY_FROM_PRODUCTS", "WebStory", s.getId(), title);
        return view(s);
    }

    // ------------------------------------------------------------------ storefront

    @Transactional(readOnly = true)
    public List<StoryCard> cards(Storefront sf, int limit) {
        return stories.findByStorefrontAndStatusOrderByPublishedAtDesc(sf, WebStory.Status.PUBLISHED,
                        PageRequest.of(0, Math.max(1, Math.min(limit, 50)))).stream()
                .map(s -> new StoryCard(s.getTitle(), s.getSlug(), s.path(), s.coverUrl(), s.getPublishedAt(), s.getPages().size() + 2))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WebStory> allPublished() {
        return stories.findByStatus(WebStory.Status.PUBLISHED);
    }

    /** The AMP page for a story; drafts only for this storefront's staff (as a no-index preview). */
    @Transactional(readOnly = true)
    public Optional<Rendered> render(Storefront sf, String slug, boolean staff) {
        Optional<WebStory> found = stories.findByStorefrontAndSlug(sf, slug);
        if (found.isEmpty()) return Optional.empty();
        WebStory s = found.get();
        boolean preview = !s.isPublished();
        if (preview && (!staff || s.getCoverKey() == null || s.getPages().isEmpty())) return Optional.empty();
        String base = baseUrl();
        List<Long> linked = s.getPages().stream().map(WebStoryPage::getProductId).filter(Objects::nonNull).distinct().toList();
        Map<Long, WebStoryRenderer.ProductLink> links = new HashMap<>();
        for (CatalogPublicService.ProductCard c : catalog.cards(sf, linked)) {
            links.put(c.id(), new WebStoryRenderer.ProductLink(base + "/" + sf.path() + "/p/" + c.slug(), priceText(c)));
        }
        String store = settings.entity(sf).getStoreName();
        String html = renderer.render(new WebStoryRenderer.Model(s, store, base + logo(sf), base + "/" + sf.path(), base + s.path(),
                "#A6C93A", "#0B0D06", "Bahnschrift,\"DIN Alternate\",Barlow,\"Segoe UI\",system-ui,sans-serif", preview, links, base));
        return Optional.of(new Rendered(html, preview));
    }

    /** The store's own logo on a square background: Google needs a square publisher logo (at least 96 x 96). */
    public static String logo(Storefront sf) {
        return "/assets/img/" + (sf == Storefront.SHOP ? "elaashop" : "elaatech") + "-logo-square.png";
    }

    // ------------------------------------------------------------------ helpers

    /** What still blocks publishing (shown in the editor as a checklist). */
    static List<String> problems(WebStory s) {
        List<String> out = new ArrayList<>();
        if (s.getCoverKey() == null) out.add("add a cover photo");
        if (s.getPages().size() < MIN_PAGES) {
            out.add("add at least " + MIN_PAGES + " pages (" + s.getPages().size() + " so far)");
        }
        return out;
    }

    static List<String> tips(WebStory s) {
        List<String> out = new ArrayList<>();
        if (s.getTitle().length() > TITLE_TIP) out.add("Short titles (under " + TITLE_TIP + " characters) show in full on Google.");
        if (s.getPages().size() < 5) out.add("Stories with 5 to 20 pages do best on Google.");
        if (s.getDescription() == null) out.add("Add a one-line description; it is used when the story is shared.");
        if (s.getPages().stream().noneMatch(p -> p.getProductId() != null)) out.add("Link pages to products so readers can swipe up to the deal.");
        if (s.getPages().stream().anyMatch(p -> p.getHeadline() == null && p.getBody() == null)) {
            out.add("Pages with a short headline keep readers tapping.");
        }
        return out;
    }

    private AdminStoryView view(WebStory s) {
        List<Long> ids = s.getPages().stream().map(WebStoryPage::getProductId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> titles = new HashMap<>();
        if (!ids.isEmpty()) products.findAllById(ids).forEach(p -> titles.put(p.getId(), p.getTitle()));
        List<AdminPageView> pages = s.getPages().stream().map(p -> new AdminPageView(p.getId(), p.getMediaType().name(), p.mediaUrl(),
                p.posterUrl(), p.getWidth(), p.getHeight(), p.getAltText(), p.getHeadline(), p.getBody(), p.getProductId(),
                p.getProductId() == null ? null : titles.getOrDefault(p.getProductId(), "(removed product)"), p.getCtaLabel())).toList();
        return new AdminStoryView(s.getId(), s.getTitle(), s.getSlug(), s.getDescription(), s.getStatus().name(), s.coverUrl(),
                s.getPublishedAt(), s.getUpdatedAt(), s.path(), pages, problems(s), tips(s));
    }

    private void apply(Storefront sf, WebStoryPage p, PageInput in) {
        if (in.headline() != null) p.setHeadline(clean(in.headline(), 90, "Headline"));
        if (in.body() != null) p.setBody(clean(in.body(), 250, "Text"));
        if (in.altText() != null) p.setAltText(clean(in.altText(), 200, "Description"));
        if (in.ctaLabel() != null) p.setCtaLabel(clean(in.ctaLabel(), 40, "Button text"));
        if (in.productId() != null) {
            if (in.productId() <= 0) {
                p.setProductId(null);
            } else {
                Product product = products.findByIdAndStorefront(in.productId(), sf)
                        .orElseThrow(() -> ApiException.notFound("PRODUCT_NOT_FOUND", "Product not found"));
                p.setProductId(product.getId());
            }
        }
    }

    private WebStory require(Storefront sf, Long id) {
        return stories.findByIdAndStorefront(id, sf).orElseThrow(() -> ApiException.notFound("STORY_NOT_FOUND", "Story not found"));
    }

    private static WebStoryPage page(WebStory s, Long pageId) {
        return s.getPages().stream().filter(p -> p.getId().equals(pageId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("PAGE_NOT_FOUND", "Page not found"));
    }

    private static List<String> mediaKeys(WebStoryPage p) {
        List<String> keys = new ArrayList<>(List.of(p.getMediaKey()));
        if (p.getPosterKey() != null) keys.add(p.getPosterKey());
        return keys;
    }

    private BufferedImage readImage(MultipartFile file, String what) throws IOException {
        if (file == null || file.isEmpty()) throw ApiException.badRequest("FILE_REQUIRED", "Choose a " + what + " photo");
        if (file.getSize() > MAX_IMAGE_BYTES) throw ApiException.rule("FILE_TOO_LARGE", "Photos must be 5 MB or smaller");
        byte[] bytes = file.getBytes();
        FileTypes.requireImage(Arrays.copyOf(bytes, Math.min(bytes.length, 16)));
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        if (img == null) throw ApiException.rule("UNREADABLE_IMAGE", "Use a JPEG or PNG photo for the " + what);
        if ((long) img.getWidth() * img.getHeight() > 40_000_000L) throw ApiException.rule("IMAGE_TOO_LARGE", "This photo has too many pixels");
        return img;
    }

    private String storeImage(MultipartFile file) throws IOException {
        if (file.getSize() > MAX_IMAGE_BYTES) throw ApiException.rule("FILE_TOO_LARGE", "Photos must be 5 MB or smaller");
        try (InputStream in = file.getInputStream()) {
            byte[] header = in.readNBytes(16);
            String ext = FileTypes.requireImage(header);
            return storage.store(FileStorage.Area.PUBLIC, new SequenceInputStream(new ByteArrayInputStream(header), in), ext, MAX_IMAGE_BYTES).key();
        }
    }

    private static String imageExtension(byte[] header) {
        try {
            return FileTypes.requireImage(header);
        } catch (ApiException e) {
            return null;
        }
    }

    private String uniqueSlug(Storefront sf, String wanted, Long selfId) {
        String base = CatalogAdminService.slugify(wanted);
        if (base.length() > 120) base = base.substring(0, 120).replaceAll("-+$", "");
        String slug = base;
        for (int i = 2; ; i++) {
            Optional<WebStory> other = stories.findByStorefrontAndSlug(sf, slug);
            if (other.isEmpty() || other.get().getId().equals(selfId)) return slug;
            slug = base + "-" + i;
        }
    }

    private static String title(String v) {
        String t = v == null ? "" : v.strip();
        if (t.isEmpty()) throw ApiException.badRequest("TITLE_REQUIRED", "Give the story a title");
        if (t.length() > 90) throw ApiException.badRequest("FIELD_TOO_LONG", "Titles must be at most 90 characters");
        return t;
    }

    private static String clean(String v, int max, String label) {
        if (v == null) return null;
        String t = v.strip();
        if (t.length() > max) throw ApiException.badRequest("FIELD_TOO_LONG", label + " must be at most " + max + " characters");
        return t.isEmpty() ? null : t;
    }

    private static String cut(String v, int max) {
        if (v == null || v.isBlank()) return null;
        String t = v.strip().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max - 1).strip() + "…";
    }

    private static String firstLine(String lines) {
        if (lines == null) return null;
        return Arrays.stream(lines.split("\\R")).map(String::strip).filter(l -> !l.isEmpty()).findFirst().orElse(null);
    }

    private static Integer within(Integer v, int min, int max) {
        return v == null || v < min || v > max ? null : v;
    }

    /** "₹2,149 at Croma · 20% off", or a "check the price" line when the store hides or has not confirmed the price. */
    static String priceText(CatalogPublicService.ProductCard c) {
        if (c.priceMinor() != null) {
            String text = rupees(c.priceMinor()) + (c.merchantName() != null ? " at " + c.merchantName() : "");
            return c.discountPercent() != null && c.discountPercent() >= 5 ? text + " · " + c.discountPercent() + "% off" : text;
        }
        return c.merchantName() != null ? "Check the price at " + c.merchantName() : null;
    }

    static String rupees(long minor) {
        NumberFormat f = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"));
        f.setCurrency(Currency.getInstance("INR"));
        if (minor % 100 == 0) f.setMaximumFractionDigits(0);
        return f.format(BigDecimal.valueOf(minor, 2));
    }

    private String baseUrl() {
        String b = props.baseUrl() == null ? "" : props.baseUrl().trim();
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }
}
