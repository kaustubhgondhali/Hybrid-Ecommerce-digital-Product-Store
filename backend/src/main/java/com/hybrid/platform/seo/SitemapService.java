package com.hybrid.platform.seo;

import com.hybrid.platform.catalog.CatalogEnums.ProductStatus;
import com.hybrid.platform.catalog.Category;
import com.hybrid.platform.catalog.CategoryRepository;
import com.hybrid.platform.catalog.Product;
import com.hybrid.platform.catalog.ProductRepository;
import com.hybrid.platform.commerce.settings.SettingsService;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.content.ContentPage;
import com.hybrid.platform.content.ContentService;
import com.hybrid.platform.catalog.CatalogPublicService;
import com.hybrid.platform.guides.Guide;
import com.hybrid.platform.guides.GuideService;
import com.hybrid.platform.offers.OfferService;
import com.hybrid.platform.stories.WebStory;
import com.hybrid.platform.stories.WebStoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static com.hybrid.platform.seo.SeoPage.esc;

@Service
public class SitemapService {

    public record SitemapEntry(String url, Instant lastModified, List<String> images) {
    }

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final ContentService content;
    private final SettingsService settings;
    private final SeoPageService pages;
    private final WebStoryService stories;
    private final CatalogPublicService catalog;
    private final OfferService offers;
    private final GuideService guides;

    public SitemapService(ProductRepository products, CategoryRepository categories, ContentService content,
                          SettingsService settings, SeoPageService pages, WebStoryService stories,
                          CatalogPublicService catalog, OfferService offers, GuideService guides) {
        this.guides = guides;
        this.catalog = catalog;
        this.offers = offers;
        this.products = products;
        this.categories = categories;
        this.content = content;
        this.settings = settings;
        this.pages = pages;
        this.stories = stories;
    }

    @Transactional(readOnly = true)
    public List<SitemapEntry> entries() {
        List<SitemapEntry> out = new ArrayList<>();
        out.add(new SitemapEntry(pages.abs("/"), null, List.of()));
        for (Storefront sf : Storefront.values()) {
            List<Product> published = products.findByStorefrontAndStatusOrderByPublishedAtDesc(sf, ProductStatus.PUBLISHED);
            Instant newest = published.stream().map(Product::getUpdatedAt).max(Instant::compareTo).orElse(null);
            out.add(new SitemapEntry(pages.abs("/" + sf.path()), newest, List.of()));
            for (Category c : categories.findByStorefrontAndActiveTrueOrderBySortOrderAscNameAsc(sf)) {
                boolean hasProducts = published.stream().anyMatch(p -> c.getId().equals(p.getCategoryId()));
                if (hasProducts) out.add(new SitemapEntry(pages.abs("/" + sf.path() + "/c/" + c.getSlug()), null, List.of()));
            }
            for (Product p : published) {
                if (p.isNoindex()) continue;
                out.add(new SitemapEntry(pages.abs("/" + sf.path() + "/p/" + p.getSlug()), p.getUpdatedAt(),
                        p.getImages().stream().limit(10).map(i -> pages.abs(i.url())).toList()));
            }
            for (ContentPage c : content.allPublished(sf)) {
                out.add(new SitemapEntry(pages.abs(ContentService.url(c)), c.getUpdatedAt(), List.of()));
            }
        }
        // Coupons page and one page per partner store.
        for (Storefront sf : Storefront.values()) {
            if (!offers.live(sf, null, null).isEmpty()) out.add(new SitemapEntry(pages.abs("/" + sf.path() + "/offers"), null, List.of()));
            for (CatalogPublicService.StoreView st : catalog.stores(sf)) {
                out.add(new SitemapEntry(pages.abs("/" + sf.path() + "/store/" + st.domain()), null, List.of()));
            }
        }
        // Buying guides: the list page and every published guide.
        List<Guide> allGuides = guides.allPublished();
        for (Storefront sf : Storefront.values()) {
            List<Guide> mine = allGuides.stream().filter(g -> g.getStorefront() == sf).toList();
            if (mine.isEmpty()) continue;
            out.add(new SitemapEntry(pages.abs("/" + sf.path() + "/guides"),
                    mine.stream().map(Guide::getUpdatedAt).max(Instant::compareTo).orElse(null), List.of()));
            for (Guide g : mine) out.add(new SitemapEntry(pages.abs(g.path()), g.getUpdatedAt(), List.of()));
        }
        // Web Stories: listed so Google can find them for its Web Stories results.
        List<WebStory> published = stories.allPublished();
        for (Storefront sf : Storefront.values()) {
            List<WebStory> mine = published.stream().filter(w -> w.getStorefront() == sf).toList();
            if (mine.isEmpty()) continue;
            out.add(new SitemapEntry(pages.abs("/" + sf.path() + "/stories"),
                    mine.stream().map(WebStory::getUpdatedAt).max(Instant::compareTo).orElse(null), List.of()));
            for (WebStory w : mine) {
                out.add(new SitemapEntry(pages.abs(w.path()), w.getUpdatedAt(),
                        w.coverUrl() == null ? List.of() : List.of(pages.abs(w.coverUrl()))));
            }
        }
        return out;
    }

    /** Read-only transaction: building entries loads product images lazily (open-in-view is off). */
    @Transactional(readOnly = true)
    public String sitemapXml() {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\" ")
                .append("xmlns:image=\"http://www.google.com/schemas/sitemap-image/1.1\">\n");
        for (SitemapEntry e : entries()) {
            sb.append("  <url><loc>").append(esc(e.url())).append("</loc>");
            if (e.lastModified() != null) {
                sb.append("<lastmod>").append(e.lastModified().truncatedTo(ChronoUnit.SECONDS)).append("</lastmod>");
            }
            for (String img : e.images()) {
                sb.append("<image:image><image:loc>").append(esc(img)).append("</image:loc></image:image>");
            }
            sb.append("</url>\n");
        }
        return sb.append("</urlset>\n").toString();
    }

    @Transactional
    public String robotsTxt() {
        StringBuilder sb = new StringBuilder("User-agent: *\n");
        for (Storefront sf : Storefront.values()) {
            String b = "/" + sf.path();
            sb.append("Disallow: ").append(b).append("/admin\n")
                    .append("Disallow: ").append(b).append("/cart\n")
                    .append("Disallow: ").append(b).append("/checkout\n")
                    .append("Disallow: ").append(b).append("/pay/\n")
                    .append("Disallow: ").append(b).append("?q=\n");
        }
        sb.append("Disallow: /account\nDisallow: /api/\nDisallow: /go/\nAllow: /\n");
        for (Storefront sf : Storefront.values()) {
            String extra = settings.entity(sf).getRobotsExtra();
            if (extra != null && !extra.isBlank()) sb.append("\n# ").append(sf.path()).append('\n').append(extra.strip()).append('\n');
        }
        sb.append("\nSitemap: ").append(pages.abs("/sitemap.xml")).append('\n');
        return sb.toString();
    }
}
