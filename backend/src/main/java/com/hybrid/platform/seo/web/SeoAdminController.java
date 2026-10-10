package com.hybrid.platform.seo.web;

import com.hybrid.platform.catalog.CatalogAdminService;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.security.AdminPrincipal;
import com.hybrid.platform.security.CurrentUser;
import com.hybrid.platform.seo.SeoAdminService;
import com.hybrid.platform.seo.SeoRedirectService;
import com.hybrid.platform.seo.SitemapService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/{storefront}/admin/seo")
public class SeoAdminController {

    public record ProductSeoRequest(@Size(max = 160) String slug, @Size(max = 200) String metaTitle,
                                    @Size(max = 320) String metaDescription, @Size(max = 100) String focusKeyword,
                                    Boolean noindex) {
    }

    private final SeoAdminService seo;
    private final SeoRedirectService redirects;
    private final SitemapService sitemap;
    private final CatalogAdminService catalog;

    public SeoAdminController(SeoAdminService seo, SeoRedirectService redirects, SitemapService sitemap,
                              CatalogAdminService catalog) {
        this.seo = seo;
        this.redirects = redirects;
        this.sitemap = sitemap;
        this.catalog = catalog;
    }

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('PERM_CATALOG_READ')")
    public SeoAdminService.Overview overview(@PathVariable String storefront) {
        return seo.overview(sf(storefront));
    }

    @GetMapping("/products")
    @PreAuthorize("hasAuthority('PERM_CATALOG_READ')")
    public List<SeoAdminService.ProductRow> products(@PathVariable String storefront) {
        return seo.productRows(sf(storefront));
    }

    @GetMapping("/products/{id}")
    @PreAuthorize("hasAuthority('PERM_CATALOG_READ')")
    public SeoAdminService.ProductSeo product(@PathVariable String storefront, @PathVariable Long id) {
        return seo.product(sf(storefront), id, null);
    }

    /** Live analysis of unsaved edits, used while typing in the SEO editor. */
    @PostMapping("/products/{id}/analyze")
    @PreAuthorize("hasAuthority('PERM_CATALOG_READ')")
    public SeoAdminService.ProductSeo analyze(@PathVariable String storefront, @PathVariable Long id,
                                              @RequestBody SeoAdminService.DraftInput draft) {
        return seo.product(sf(storefront), id, draft);
    }

    @PutMapping("/products/{id}")
    @PreAuthorize("hasAuthority('PERM_CATALOG_WRITE')")
    public SeoAdminService.ProductSeo save(@PathVariable String storefront, @PathVariable Long id,
                                           @Valid @RequestBody ProductSeoRequest req) {
        AdminPrincipal admin = CurrentUser.admin(Storefront.fromPath(storefront));
        catalog.update(admin, id, new CatalogAdminService.ProductInput(null, null, null, req.slug(), null, null, null,
                null, null, null, null, null, null, null, req.metaTitle() == null ? "" : req.metaTitle(),
                req.metaDescription() == null ? "" : req.metaDescription(),
                req.focusKeyword() == null ? "" : req.focusKeyword(), req.noindex(), null, null), null);
        return seo.product(admin.storefront(), id, null);
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('PERM_CATALOG_READ')")
    public SeoAdminService.SettingsView settings(@PathVariable String storefront) {
        return seo.settings(sf(storefront));
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_WRITE')")
    public SeoAdminService.SettingsView updateSettings(@PathVariable String storefront,
                                                       @RequestBody SeoAdminService.SettingsInput input) {
        return seo.updateSettings(CurrentUser.admin(Storefront.fromPath(storefront)), input);
    }

    @GetMapping("/sitemap")
    @PreAuthorize("hasAuthority('PERM_CATALOG_READ')")
    public List<SitemapService.SitemapEntry> sitemapEntries(@PathVariable String storefront) {
        Storefront s = sf(storefront);
        String marker = "/" + s.path();
        return sitemap.entries().stream().filter(e -> e.url().contains(marker + "/") || e.url().endsWith(marker)).toList();
    }

    // ------------------------------------------------------------------ redirects & 404 monitor

    @GetMapping("/redirects")
    @PreAuthorize("hasAuthority('PERM_CATALOG_READ')")
    public List<SeoRedirectService.RedirectView> listRedirects(@PathVariable String storefront) {
        return redirects.list(sf(storefront));
    }

    @PostMapping("/redirects")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_SETTINGS_WRITE')")
    public SeoRedirectService.RedirectView createRedirect(@PathVariable String storefront,
                                                          @RequestBody SeoRedirectService.RedirectInput input) {
        return redirects.create(CurrentUser.admin(Storefront.fromPath(storefront)), input);
    }

    @PutMapping("/redirects/{id}")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_WRITE')")
    public SeoRedirectService.RedirectView updateRedirect(@PathVariable String storefront, @PathVariable Long id,
                                                          @RequestBody SeoRedirectService.RedirectInput input) {
        return redirects.update(CurrentUser.admin(Storefront.fromPath(storefront)), id, input);
    }

    @DeleteMapping("/redirects/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_SETTINGS_WRITE')")
    public void deleteRedirect(@PathVariable String storefront, @PathVariable Long id) {
        redirects.delete(CurrentUser.admin(Storefront.fromPath(storefront)), id);
    }

    @GetMapping("/not-found")
    @PreAuthorize("hasAuthority('PERM_CATALOG_READ')")
    public List<SeoRedirectService.NotFoundView> notFound(@PathVariable String storefront) {
        return redirects.notFound(sf(storefront));
    }

    @DeleteMapping("/not-found/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_SETTINGS_WRITE')")
    public void dismissNotFound(@PathVariable String storefront, @PathVariable Long id) {
        redirects.clearNotFound(CurrentUser.admin(Storefront.fromPath(storefront)), id);
    }

    @DeleteMapping("/not-found")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_SETTINGS_WRITE')")
    public void clearNotFound(@PathVariable String storefront) {
        redirects.clearNotFound(CurrentUser.admin(Storefront.fromPath(storefront)), null);
    }

    private static Storefront sf(String storefront) {
        Storefront s = Storefront.fromPath(storefront);
        CurrentUser.admin(s);
        return s;
    }
}
