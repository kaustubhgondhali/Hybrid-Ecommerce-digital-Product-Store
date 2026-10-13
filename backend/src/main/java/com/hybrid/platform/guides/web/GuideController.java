package com.hybrid.platform.guides.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.guides.GuideService;
import com.hybrid.platform.security.CurrentUser;
import com.hybrid.platform.seo.HtmlShells;
import com.hybrid.platform.seo.SeoPageService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Buying guides: admin editing and preview, and the public list used by the storefront scripts. */
@RestController
public class GuideController {

    private final GuideService service;
    private final SeoPageService pages;
    private final HtmlShells shells;
    private final ObjectMapper mapper;

    public GuideController(GuideService service, SeoPageService pages, HtmlShells shells, ObjectMapper mapper) {
        this.service = service;
        this.pages = pages;
        this.shells = shells;
        this.mapper = mapper;
    }

    @GetMapping("/api/{storefront}/admin/guides")
    @PreAuthorize("hasAuthority('PERM_CATALOG_READ')")
    public List<GuideService.AdminGuideRow> list(@PathVariable String storefront) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return service.list(sf);
    }

    @GetMapping("/api/{storefront}/admin/guides/{id}")
    @PreAuthorize("hasAuthority('PERM_CATALOG_READ')")
    public GuideService.AdminGuideView get(@PathVariable String storefront, @PathVariable Long id) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return service.get(sf, id);
    }

    @PostMapping("/api/{storefront}/admin/guides")
    @PreAuthorize("hasAuthority('PERM_CATALOG_WRITE')")
    @ResponseStatus(HttpStatus.CREATED)
    public GuideService.AdminGuideView create(@PathVariable String storefront, @RequestBody GuideService.GuideInput in) {
        return service.create(CurrentUser.admin(Storefront.fromPath(storefront)), in);
    }

    @PutMapping("/api/{storefront}/admin/guides/{id}")
    @PreAuthorize("hasAuthority('PERM_CATALOG_WRITE')")
    public GuideService.AdminGuideView update(@PathVariable String storefront, @PathVariable Long id, @RequestBody GuideService.GuideInput in) {
        return service.update(CurrentUser.admin(Storefront.fromPath(storefront)), id, in);
    }

    @DeleteMapping("/api/{storefront}/admin/guides/{id}")
    @PreAuthorize("hasAuthority('PERM_CATALOG_WRITE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String storefront, @PathVariable Long id) {
        service.delete(CurrentUser.admin(Storefront.fromPath(storefront)), id);
    }

    /** The saved guide exactly as shoppers will see it, also for drafts (opened in a new tab from the editor). */
    @GetMapping("/api/{storefront}/admin/guides/{id}/preview")
    @PreAuthorize("hasAuthority('PERM_CATALOG_READ')")
    public ResponseEntity<String> preview(@PathVariable String storefront, @PathVariable Long id) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        String html = pages.guidePage(sf, service.entity(sf, id), true).renderInto(shells.load("store/page.html"), mapper);
        return ResponseEntity.ok().contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore()).header("X-Robots-Tag", "noindex, nofollow").body(html);
    }

    /** Published guides, newest first; with {@code product} only the guides that show that product. */
    @GetMapping("/api/{storefront}/catalog/guides")
    public List<GuideService.GuideCard> published(@PathVariable String storefront, @RequestParam(required = false) Long product,
                                                  @RequestParam(defaultValue = "12") int limit) {
        return service.cards(Storefront.fromPath(storefront), product, limit);
    }
}
