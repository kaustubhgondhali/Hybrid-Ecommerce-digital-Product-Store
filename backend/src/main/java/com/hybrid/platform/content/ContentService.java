package com.hybrid.platform.content;

import com.hybrid.platform.commerce.settings.SettingsService;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.audit.AuditService;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.security.AdminPrincipal;
import com.hybrid.platform.seo.SeoRedirectService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
public class ContentService {

    public record PageView(Long id, String slug, String title, String body, String metaTitle, String metaDescription,
                           boolean published, boolean showInFooter, int sortOrder, String url, Instant updatedAt) {
    }

    public record PageInput(String slug, String title, String body, String metaTitle, String metaDescription,
                            Boolean published, Boolean showInFooter, Integer sortOrder) {
    }

    public record FooterLink(String title, String url) {
    }

    private final ContentPageRepository pages;
    private final SettingsService settings;
    private final SeoRedirectService redirects;
    private final AuditService audit;

    public ContentService(ContentPageRepository pages, SettingsService settings, SeoRedirectService redirects,
                          AuditService audit) {
        this.pages = pages;
        this.settings = settings;
        this.redirects = redirects;
        this.audit = audit;
    }

    /** Seeds starter policy pages once, for storefronts that have no pages yet. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedDefaults() {
        for (Storefront sf : Storefront.values()) {
            if (pages.countByStorefront(sf) > 0) continue;
            var store = settings.entity(sf);
            String email = store.getSupportEmail() == null ? "[support email]" : store.getSupportEmail();
            int order = 0;
            for (ContentDefaults.Template t : ContentDefaults.forStorefront(sf)) {
                ContentPage p = new ContentPage(sf, t.slug(), t.title(),
                        t.body().replace("{{STORE}}", store.getStoreName()).replace("{{EMAIL}}", email), order++);
                p.setMetaDescription(t.metaDescription());
                pages.save(p);
            }
        }
    }

    @Transactional(readOnly = true)
    public List<PageView> list(Storefront sf) {
        return pages.findByStorefrontOrderBySortOrderAscTitleAsc(sf).stream().map(ContentService::view).toList();
    }

    @Transactional(readOnly = true)
    public PageView get(Storefront sf, Long id) {
        return view(require(sf, id));
    }

    @Transactional(readOnly = true)
    public List<FooterLink> footer(Storefront sf) {
        return pages.findByStorefrontAndPublishedTrueOrderBySortOrderAscTitleAsc(sf).stream()
                .filter(ContentPage::isShowInFooter).map(p -> new FooterLink(p.getTitle(), url(p))).toList();
    }

    @Transactional(readOnly = true)
    public java.util.Optional<ContentPage> published(Storefront sf, String slug) {
        return pages.findByStorefrontAndSlug(sf, slug).filter(ContentPage::isPublished);
    }

    @Transactional(readOnly = true)
    public List<ContentPage> allPublished(Storefront sf) {
        return pages.findByStorefrontAndPublishedTrueOrderBySortOrderAscTitleAsc(sf);
    }

    @Transactional
    public PageView create(AdminPrincipal admin, PageInput in) {
        Storefront sf = admin.storefront();
        String title = requireTitle(in.title());
        String slug = slugify(in.slug() == null || in.slug().isBlank() ? title : in.slug());
        if (pages.existsByStorefrontAndSlug(sf, slug)) {
            throw ApiException.conflict("PAGE_EXISTS", "A page with this URL already exists");
        }
        ContentPage p = new ContentPage(sf, slug, title, body(in.body()), in.sortOrder() == null ? 100 : in.sortOrder());
        apply(p, in);
        pages.save(p);
        audit.record(admin, "PAGE_CREATED", "ContentPage", p.getId(), p.getSlug());
        return view(p);
    }

    @Transactional
    public PageView update(AdminPrincipal admin, Long id, PageInput in) {
        ContentPage p = require(admin.storefront(), id);
        if (in.title() != null) p.setTitle(requireTitle(in.title()));
        if (in.body() != null) p.setBody(body(in.body()));
        if (in.slug() != null && !in.slug().isBlank()) {
            String slug = slugify(in.slug());
            if (!slug.equals(p.getSlug())) {
                if (pages.existsByStorefrontAndSlug(admin.storefront(), slug)) {
                    throw ApiException.conflict("PAGE_EXISTS", "A page with this URL already exists");
                }
                String oldUrl = url(p);
                p.setSlug(slug);
                redirects.autoRedirect(admin.storefront(), oldUrl, url(p));
            }
        }
        apply(p, in);
        audit.record(admin, "PAGE_UPDATED", "ContentPage", id, p.getSlug());
        return view(p);
    }

    @Transactional
    public void delete(AdminPrincipal admin, Long id) {
        ContentPage p = require(admin.storefront(), id);
        pages.delete(p);
        audit.record(admin, "PAGE_DELETED", "ContentPage", id, p.getSlug());
    }

    private void apply(ContentPage p, PageInput in) {
        if (in.metaTitle() != null) p.setMetaTitle(trimTo(in.metaTitle(), 200));
        if (in.metaDescription() != null) p.setMetaDescription(trimTo(in.metaDescription(), 320));
        if (in.published() != null) p.setPublished(in.published());
        if (in.showInFooter() != null) p.setShowInFooter(in.showInFooter());
        if (in.sortOrder() != null) p.setSortOrder(in.sortOrder());
    }

    private ContentPage require(Storefront sf, Long id) {
        return pages.findByIdAndStorefront(id, sf)
                .orElseThrow(() -> ApiException.notFound("PAGE_NOT_FOUND", "Page not found"));
    }

    public static String url(ContentPage p) {
        return "/" + p.getStorefront().path() + "/page/" + p.getSlug();
    }

    private static PageView view(ContentPage p) {
        return new PageView(p.getId(), p.getSlug(), p.getTitle(), p.getBody(), p.getMetaTitle(), p.getMetaDescription(),
                p.isPublished(), p.isShowInFooter(), p.getSortOrder(), url(p), p.getUpdatedAt());
    }

    private static String requireTitle(String title) {
        if (title == null || title.isBlank()) throw ApiException.badRequest("FIELD_REQUIRED", "Title is required");
        String t = title.trim();
        if (t.length() > 200) throw ApiException.badRequest("FIELD_TOO_LONG", "Title is too long");
        return t;
    }

    private static String body(String body) {
        String b = body == null ? "" : body.strip();
        if (b.length() > 60_000) throw ApiException.badRequest("FIELD_TOO_LONG", "Page content is too long");
        return b;
    }

    private static String trimTo(String v, int max) {
        String t = v.trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }

    static String slugify(String value) {
        String s = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (s.isEmpty()) s = "page";
        return s.length() > 140 ? s.substring(0, 140).replaceAll("-+$", "") : s;
    }
}
