package com.hybrid.platform.research;

import com.hybrid.platform.affiliate.AffiliateMerchant;
import com.hybrid.platform.affiliate.AffiliateRepositories.MerchantRepository;
import com.hybrid.platform.catalog.Category;
import com.hybrid.platform.catalog.CategoryRepository;
import com.hybrid.platform.catalog.CatalogEnums.ProductStatus;
import com.hybrid.platform.catalog.Product;
import com.hybrid.platform.catalog.ProductRepository;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.research.AffiliateResearchAnalyzer.CatalogSnapshot;
import com.hybrid.platform.research.AffiliateResearchAnalyzer.Report;
import com.hybrid.platform.research.AffiliateResearchAnalyzer.Request;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AffiliateResearchService {

    public record PlatformCard(String id, String name, String program, String access, String region, String joinUrl,
                               String requirement, List<String> strengths, List<String> storefronts) {
    }

    public record CategoryCard(String id, String name, String blurb) {
    }

    public record Meta(String storefront, String howItWorks, List<PlatformCard> platforms,
                       List<CategoryCard> categories, int catalogProducts, int connectedMerchants) {
    }

    public record AnalyzeBody(String niche, String researchNotes, List<String> categoryFocus, Integer maxPerCategory) {
    }

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final MerchantRepository merchants;

    public AffiliateResearchService(ProductRepository products, CategoryRepository categories,
                                    MerchantRepository merchants) {
        this.products = products;
        this.categories = categories;
        this.merchants = merchants;
    }

    @Transactional(readOnly = true)
    public Meta meta(Storefront sf) {
        List<PlatformCard> platforms = AffiliatePlatformCatalog.platforms().stream()
                .filter(p -> p.storefronts().contains(sf))
                .map(p -> new PlatformCard(p.id(), p.name(), p.program(), p.access().name(), p.region(), p.joinUrl(),
                        p.requirement(), p.strengths(), p.storefronts().stream().map(Storefront::path).toList()))
                .toList();
        List<CategoryCard> cats = AffiliatePlatformCatalog.playbooks(sf).stream()
                .map(b -> new CategoryCard(b.id(), b.name(), b.blurb()))
                .toList();
        long catalogCount = products.findByStorefrontAndStatusNotOrderByUpdatedAtDesc(sf, ProductStatus.ARCHIVED).size();
        int merchantCount = (int) merchants.findByStorefrontOrderByNameAsc(sf).stream().filter(AffiliateMerchant::isActive).count();
        return new Meta(sf.path(),
                "Paste Google reviews, roundups, or forum notes you collected. The tool ranks a built-in playbook of "
                        + "product ideas against your notes and catalog, lists only platforms with a real affiliate program "
                        + "(checked " + AffiliatePlatformCatalog.VERIFIED_ON + "), and suggests the Google searches to run next. "
                        + "It does not fetch Google results itself, so it is instant and nothing leaves your server.",
                platforms, cats, (int) catalogCount, merchantCount);
    }

    @Transactional(readOnly = true)
    public Report analyze(Storefront sf, AnalyzeBody body) {
        if (body == null) {
            throw ApiException.badRequest("BODY_REQUIRED", "Send niche and optional research notes");
        }
        String niche = body.niche() == null ? "" : body.niche().trim();
        String notes = body.researchNotes() == null ? "" : body.researchNotes().trim();
        if (niche.length() > 120) {
            throw ApiException.badRequest("NICHE_TOO_LONG", "Keep the niche under 120 characters");
        }
        if (notes.length() > 20_000) {
            throw ApiException.badRequest("NOTES_TOO_LONG", "Keep research notes under 20,000 characters");
        }
        List<Product> catalog = products.findByStorefrontAndStatusNotOrderByUpdatedAtDesc(sf, ProductStatus.ARCHIVED);
        List<String> titles = catalog.stream().map(Product::getTitle).toList();
        List<String> catNames = categories.findByStorefrontOrderBySortOrderAscNameAsc(sf).stream()
                .map(Category::getName).toList();
        List<String> hosts = merchants.findByStorefrontOrderByNameAsc(sf).stream()
                .filter(AffiliateMerchant::isActive)
                .map(AffiliateMerchant::getDomain)
                .filter(d -> d != null && !d.isBlank())
                .toList();

        Request req = new Request(sf, niche, notes,
                body.categoryFocus() == null ? List.of() : body.categoryFocus(),
                body.maxPerCategory() == null ? 4 : body.maxPerCategory());
        return AffiliateResearchAnalyzer.analyze(req, new CatalogSnapshot(titles, catNames, hosts));
    }
}
