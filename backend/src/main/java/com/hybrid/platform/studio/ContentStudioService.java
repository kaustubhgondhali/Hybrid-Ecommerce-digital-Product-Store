package com.hybrid.platform.studio;

import com.hybrid.platform.catalog.CatalogEnums.ProductStatus;
import com.hybrid.platform.catalog.CatalogPublicService;
import com.hybrid.platform.catalog.Product;
import com.hybrid.platform.catalog.ProductImage;
import com.hybrid.platform.catalog.ProductRepository;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.crm.CampaignService;
import com.hybrid.platform.crm.MarketingMailer;
import com.hybrid.platform.guides.GuideRepository;
import com.hybrid.platform.guides.GuideService;
import com.hybrid.platform.security.AdminPrincipal;
import com.hybrid.platform.stories.StoryImages;
import com.hybrid.platform.stories.WebStoryService;
import com.hybrid.platform.studio.ContentWriter.BlogDraft;
import com.hybrid.platform.studio.ContentWriter.BlogKind;
import com.hybrid.platform.studio.ContentWriter.EmailDraft;
import com.hybrid.platform.studio.ContentWriter.EmailKind;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * The content studio: turns published products into blog posts (buying guides), Web Stories, newsletter drafts, social
 * posts and posters. Everything is written by {@link ContentWriter} templates and drawn by {@link PosterRenderer}, so
 * it costs nothing to run and needs no outside service. Newsletters are only ever saved as drafts.
 */
@Service
public class ContentStudioService {

    static final int MAX_MULTI = 10;
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    public record ProductPreview(ProductFacts product, List<BlogDraft> blogs, ContentWriter.StoryDraft story,
                                 List<EmailDraft> emails, List<ContentWriter.SocialPost> social, boolean storyReady) {
    }

    public record MultiPreview(List<ProductFacts> products, BlogDraft comparison, BlogDraft roundup, List<EmailDraft> emails) {
    }

    /** What was created and where to edit it in the admin. */
    public record Created(String kind, Long id, String title, String status, String adminPath) {
    }

    public record SettingsView(String autoBlog, String blogKind, String autoStory, Boolean autoNewsletter) {
    }

    public record LogEntry(Long id, String kind, Long productId, Long targetId, String title, boolean automatic,
                           java.time.Instant createdAt, String adminPath) {
    }

    private final ProductRepository products;
    private final CatalogPublicService catalog;
    private final GuideService guides;
    private final GuideRepository guideRepo;
    private final WebStoryService stories;
    private final CampaignService campaigns;
    private final MarketingMailer mailer;
    private final StoryImages images;
    private final StudioRepositories.SettingsRepository settings;
    private final StudioRepositories.LogRepository log;

    public ContentStudioService(ProductRepository products, CatalogPublicService catalog, GuideService guides,
                                GuideRepository guideRepo, WebStoryService stories, CampaignService campaigns,
                                MarketingMailer mailer, StoryImages images, StudioRepositories.SettingsRepository settings,
                                StudioRepositories.LogRepository log) {
        this.products = products;
        this.catalog = catalog;
        this.guides = guides;
        this.guideRepo = guideRepo;
        this.stories = stories;
        this.campaigns = campaigns;
        this.mailer = mailer;
        this.images = images;
        this.settings = settings;
        this.log = log;
    }

    // ------------------------------------------------------------------ previews

    @Transactional(readOnly = true)
    public ProductPreview preview(Storefront sf, Long productId, int variant) {
        ProductFacts p = facts(sf, productId);
        List<ProductFacts> related = related(sf, p);
        ContentWriter.Store store = store(sf);
        List<BlogDraft> blogs = new ArrayList<>();
        for (BlogKind k : List.of(BlogKind.REVIEW, BlogKind.WORTH_IT, BlogKind.FAQ, BlogKind.BUYING_TIPS)) {
            blogs.add(ContentWriter.blog(k, p, related, store, variant));
        }
        List<ProductFacts> withRelated = new ArrayList<>(List.of(p));
        withRelated.addAll(related);
        List<EmailDraft> emails = List.of(ContentWriter.email(EmailKind.LAUNCH, List.of(p), null, store, variant));
        if (withRelated.size() > 1) {
            emails = List.of(emails.get(0), ContentWriter.email(EmailKind.NEW_ARRIVALS, withRelated, null, store, variant));
        }
        return new ProductPreview(p, blogs, ContentWriter.story(p, store, variant), emails,
                ContentWriter.social(p, store, variant), p.imageKey() != null);
    }

    @Transactional(readOnly = true)
    public MultiPreview previewMany(Storefront sf, List<Long> ids, String topic, int variant) {
        List<ProductFacts> list = factsList(sf, ids, 2);
        ContentWriter.Store store = store(sf);
        BlogDraft comparison = list.size() <= 4 ? ContentWriter.comparison(list, store, variant) : null;
        BlogDraft roundup = list.size() >= 3 ? ContentWriter.roundup(topicOr(topic, list), list, store, variant) : null;
        List<EmailDraft> emails = List.of(
                ContentWriter.email(EmailKind.NEW_ARRIVALS, list, null, store, variant),
                ContentWriter.email(EmailKind.TOP_DEALS, list, null, store, variant),
                ContentWriter.email(EmailKind.COLLECTION, list, topicOr(topic, list), store, variant));
        return new MultiPreview(list, comparison, roundup, emails);
    }

    // ------------------------------------------------------------------ create

    @Transactional
    public Created createBlog(AdminPrincipal admin, Long productId, BlogKind kind, int variant, boolean publish, boolean automatic) {
        Storefront sf = admin.storefront();
        if (kind == BlogKind.COMPARISON || kind == BlogKind.ROUNDUP) {
            throw ApiException.badRequest("CHOOSE_PRODUCTS", "Comparisons and roundups need several products");
        }
        ProductFacts p = facts(sf, productId);
        BlogDraft d = ContentWriter.blog(kind, p, related(sf, p), store(sf), variant);
        return saveGuide(admin, d, productId, publish, automatic);
    }

    @Transactional
    public Created createMultiBlog(AdminPrincipal admin, List<Long> ids, BlogKind kind, String topic, int variant, boolean publish) {
        Storefront sf = admin.storefront();
        List<ProductFacts> list = factsList(sf, ids, kind == BlogKind.ROUNDUP ? 3 : 2);
        BlogDraft d = switch (kind) {
            case COMPARISON -> {
                if (list.size() > 4) throw ApiException.badRequest("CHOOSE_PRODUCTS", "Compare 2 to 4 products");
                yield ContentWriter.comparison(list, store(sf), variant);
            }
            case ROUNDUP -> ContentWriter.roundup(topicOr(topic, list), list, store(sf), variant);
            default -> throw ApiException.badRequest("INVALID_KIND", "Choose a comparison or a roundup");
        };
        return saveGuide(admin, d, null, publish, false);
    }

    @Transactional
    public Created createStory(AdminPrincipal admin, Long productId, int variant, boolean publish, boolean automatic) {
        Storefront sf = admin.storefront();
        ProductFacts p = facts(sf, productId);
        ContentWriter.StoryDraft d = ContentWriter.story(p, store(sf), variant);
        List<WebStoryService.PageText> pages = d.pages().stream()
                .map(x -> new WebStoryService.PageText(x.headline(), x.body(), x.cta())).toList();
        WebStoryService.AdminStoryView v;
        try {
            v = stories.fromProduct(admin, new WebStoryService.FromProductInput(productId, d.title(), d.description(), pages, publish));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        log.save(new GeneratedContent(sf, productId, GeneratedContent.Kind.STORY, v.id(), v.title(), automatic));
        return new Created("STORY", v.id(), v.title(), v.status(), "#/stories/" + v.id());
    }

    /** Saves a newsletter as a draft campaign for subscribers; the admin reviews and sends it from Campaigns. */
    @Transactional
    public Created createNewsletter(AdminPrincipal admin, List<Long> ids, EmailKind kind, String topic, int variant, boolean automatic) {
        Storefront sf = admin.storefront();
        List<ProductFacts> list = factsList(sf, ids, 1);
        EmailDraft d = ContentWriter.email(kind, list, topicOr(topic, list), store(sf), variant);
        CampaignService.CampaignView c = campaigns.create(admin, new CampaignService.CampaignInput(d.name(), null, d.subject(), d.body(), null));
        log.save(new GeneratedContent(sf, list.size() == 1 ? list.get(0).id() : null, GeneratedContent.Kind.NEWSLETTER, c.id(), c.name(), automatic));
        return new Created("NEWSLETTER", c.id(), c.name(), "DRAFT", "#/crm/campaigns/" + c.id());
    }

    private Created saveGuide(AdminPrincipal admin, BlogDraft d, Long productId, boolean publish, boolean automatic) {
        Storefront sf = admin.storefront();
        String slug = d.slug();
        for (int i = 2; guideRepo.findByStorefrontAndSlug(sf, slug).isPresent(); i++) slug = d.slug() + "-" + i;
        GuideService.AdminGuideView g = guides.create(admin, new GuideService.GuideInput(d.title(), slug, d.summary(), d.body(),
                null, d.metaTitle(), d.metaDescription(), publish));
        log.save(new GeneratedContent(sf, productId, GeneratedContent.Kind.BLOG, g.id(), g.title(), automatic));
        return new Created("BLOG", g.id(), g.title(), g.status(), "#/guides/" + g.id());
    }

    // ------------------------------------------------------------------ posters

    @Transactional(readOnly = true)
    public byte[] poster(Storefront sf, Long productId, PosterRenderer.Format format, PosterRenderer.Theme theme) {
        ProductFacts p = facts(sf, productId);
        return PosterRenderer.product(tile(p, true), mailer.storeName(sf), siteLabel(sf), format, theme);
    }

    @Transactional(readOnly = true)
    public byte[] collectionPoster(Storefront sf, List<Long> ids, String headline, String subline,
                                   PosterRenderer.Format format, PosterRenderer.Theme theme) {
        List<ProductFacts> list = factsList(sf, ids, 2);
        int best = list.stream().map(ProductFacts::discountPercent).filter(Objects::nonNull).max(Integer::compare).orElse(0);
        String head = headline != null && !headline.isBlank() ? ContentWriter.cut(headline, 80)
                : best >= 10 ? "Up to " + best + "% off" : "Our top picks";
        String sub = subline != null && !subline.isBlank() ? ContentWriter.cut(subline, 100)
                : theme == PosterRenderer.Theme.SALE ? "Limited-time deals at " + mailer.storeName(sf) : "Hand-picked at " + mailer.storeName(sf);
        List<PosterRenderer.Tile> tiles = list.stream().limit(4).map(p -> tile(p, false)).toList();
        return PosterRenderer.collection(head, sub, tiles, mailer.storeName(sf), siteLabel(sf), format, theme);
    }

    private PosterRenderer.Tile tile(ProductFacts p, boolean tagline) {
        String tag = null;
        if (tagline) tag = !p.highlights().isEmpty() ? p.highlights().get(0) : p.summary();
        return new PosterRenderer.Tile(p.title(), tag, p.hasPrice() ? ContentWriter.inr(p.priceMinor()) : null,
                p.mrpMinor() == null ? null : ContentWriter.inr(p.mrpMinor()),
                p.discountPercent() != null && p.discountPercent() >= 5 ? p.discountPercent() : null,
                p.imageKey() == null ? null : images.read(p.imageKey()));
    }

    // ------------------------------------------------------------------ settings and history

    @Transactional(readOnly = true)
    public SettingsView settings(Storefront sf) {
        return view(settings.findById(sf).orElseGet(() -> new StudioSettings(sf)));
    }

    @Transactional
    public SettingsView updateSettings(Storefront sf, SettingsView in) {
        StudioSettings s = settings.findById(sf).orElseGet(() -> settings.save(new StudioSettings(sf)));
        if (in.autoBlog() != null) s.setAutoBlog(parse(StudioSettings.Mode.class, in.autoBlog(), "blog mode"));
        if (in.autoStory() != null) s.setAutoStory(parse(StudioSettings.Mode.class, in.autoStory(), "story mode"));
        if (in.blogKind() != null) {
            BlogKind k = parse(BlogKind.class, in.blogKind(), "blog type");
            if (k == BlogKind.COMPARISON || k == BlogKind.ROUNDUP) throw ApiException.badRequest("INVALID_KIND", "Choose a single-product blog type");
            s.setBlogKind(k);
        }
        if (in.autoNewsletter() != null) s.setAutoNewsletter(in.autoNewsletter());
        return view(s);
    }

    @Transactional(readOnly = true)
    public StudioSettings settingsEntity(Storefront sf) {
        return settings.findById(sf).orElse(null);
    }

    @Transactional(readOnly = true)
    public boolean alreadyGenerated(Storefront sf, Long productId, GeneratedContent.Kind kind) {
        return log.existsByStorefrontAndProductIdAndKindAndAutomaticTrue(sf, productId, kind);
    }

    @Transactional(readOnly = true)
    public List<LogEntry> history(Storefront sf, int limit) {
        return log.findByStorefrontOrderByCreatedAtDesc(sf, PageRequest.of(0, Math.max(1, Math.min(limit, 200)))).stream()
                .map(g -> new LogEntry(g.getId(), g.getKind().name(), g.getProductId(), g.getTargetId(), g.getTitle(), g.isAutomatic(),
                        g.getCreatedAt(), switch (g.getKind()) {
                            case BLOG -> "#/guides/" + g.getTargetId();
                            case STORY -> "#/stories/" + g.getTargetId();
                            case NEWSLETTER -> "#/crm/campaigns/" + g.getTargetId();
                        }))
                .toList();
    }

    private static SettingsView view(StudioSettings s) {
        return new SettingsView(s.getAutoBlog().name(), s.getBlogKind().name(), s.getAutoStory().name(), s.isAutoNewsletter());
    }

    // ------------------------------------------------------------------ facts

    /** Facts for one published product of this storefront, from its live public page. */
    ProductFacts facts(Storefront sf, Long productId) {
        Product p = products.findByIdAndStorefront(productId, sf)
                .orElseThrow(() -> ApiException.notFound("PRODUCT_NOT_FOUND", "Product not found"));
        if (p.getStatus() != ProductStatus.PUBLISHED) {
            throw ApiException.rule("PRODUCT_NOT_PUBLISHED", "Publish “" + p.getTitle() + "” first: content links to its live page");
        }
        return facts(sf, p);
    }

    private ProductFacts facts(Storefront sf, Product p) {
        CatalogPublicService.ProductDetail d = catalog.detail(sf, p.getSlug());
        String imageKey = null;
        String imageUrl = null;
        if (!p.getImages().isEmpty()) {
            ProductImage img = p.getImages().get(0);
            imageKey = img.getLargeKey() != null ? img.getLargeKey() : img.getStorageKey();
            imageUrl = mailer.siteUrl(img.largeUrl());
        }
        return ProductFacts.of(d, mailer.productUrl(sf, d.slug()), imageUrl, imageKey);
    }

    private List<ProductFacts> factsList(Storefront sf, List<Long> ids, int min) {
        List<Long> clean = ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
        if (clean.size() < min || clean.size() > MAX_MULTI) {
            throw ApiException.badRequest("CHOOSE_PRODUCTS", "Choose between " + min + " and " + MAX_MULTI + " products");
        }
        return clean.stream().map(id -> facts(sf, id)).toList();
    }

    /** Up to three similar published products, used for "you may also like" sections. */
    private List<ProductFacts> related(Storefront sf, ProductFacts p) {
        List<ProductFacts> out = new ArrayList<>();
        CatalogPublicService.ProductDetail d = catalog.detail(sf, p.slug());
        if (d.similar() == null) return out;
        for (CatalogPublicService.ProductCard c : d.similar().stream().limit(3).toList()) {
            products.findByIdAndStorefront(c.id(), sf).filter(x -> x.getStatus() == ProductStatus.PUBLISHED).ifPresent(x -> {
                try {
                    out.add(facts(sf, x));
                } catch (ApiException ignored) {
                    // hidden or removed meanwhile
                }
            });
        }
        return out;
    }

    ContentWriter.Store store(Storefront sf) {
        return new ContentWriter.Store(mailer.storeName(sf), mailer.storeUrl(sf), sf == Storefront.DIGITAL, LocalDate.now(INDIA));
    }

    private String siteLabel(Storefront sf) {
        String url = mailer.storeUrl(sf).replaceFirst("^https?://", "").replaceFirst("^www\\.", "");
        return url.isBlank() ? mailer.storeName(sf) : url;
    }

    private static String topicOr(String topic, List<ProductFacts> list) {
        if (topic != null && !topic.isBlank()) return ContentWriter.cut(topic.strip(), 60);
        Map<String, Long> counts = new HashMap<>();
        list.stream().map(ProductFacts::category).filter(Objects::nonNull).forEach(c -> counts.merge(c, 1L, Long::sum));
        return counts.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(e -> ContentWriter.plural(e.getKey().toLowerCase(Locale.ROOT))).orElse(null);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String v, String label) {
        try {
            return Enum.valueOf(type, v.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_VALUE", "Unknown " + label + ": " + v);
        }
    }
}
