package com.hybrid.platform.stories.web;

import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.security.CurrentUser;
import com.hybrid.platform.stories.WebStoryService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/** Web Story builder in each admin panel. Viewing needs catalog read; changing needs catalog write. */
@RestController
@RequestMapping("/api/{storefront}/admin/stories")
public class WebStoryAdminController {

    private final WebStoryService service;

    public WebStoryAdminController(WebStoryService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_CATALOG_READ')")
    public List<WebStoryService.AdminStorySummary> list(@PathVariable String storefront) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return service.list(sf);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_CATALOG_WRITE')")
    public WebStoryService.AdminStoryView create(@PathVariable String storefront, @RequestBody WebStoryService.StoryInput input) {
        return service.create(CurrentUser.admin(Storefront.fromPath(storefront)), input);
    }

    @PostMapping("/from-products")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_CATALOG_WRITE')")
    public WebStoryService.AdminStoryView fromProducts(@PathVariable String storefront,
                                                       @RequestBody WebStoryService.FromProductsInput input) throws IOException {
        return service.fromProducts(CurrentUser.admin(Storefront.fromPath(storefront)), input);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_CATALOG_READ')")
    public WebStoryService.AdminStoryView get(@PathVariable String storefront, @PathVariable Long id) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return service.get(sf, id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_CATALOG_WRITE')")
    public WebStoryService.AdminStoryView update(@PathVariable String storefront, @PathVariable Long id,
                                                 @RequestBody WebStoryService.StoryInput input) {
        return service.update(CurrentUser.admin(Storefront.fromPath(storefront)), id, input);
    }

    @PostMapping(value = "/{id}/cover", consumes = "multipart/form-data")
    @PreAuthorize("hasAuthority('PERM_CATALOG_WRITE')")
    public WebStoryService.AdminStoryView cover(@PathVariable String storefront, @PathVariable Long id,
                                                @RequestParam("file") MultipartFile file) throws IOException {
        return service.uploadCover(CurrentUser.admin(Storefront.fromPath(storefront)), id, file);
    }

    @PostMapping(value = "/{id}/pages", consumes = "multipart/form-data")
    @PreAuthorize("hasAuthority('PERM_CATALOG_WRITE')")
    public WebStoryService.AdminStoryView addPage(@PathVariable String storefront, @PathVariable Long id,
                                                  @RequestParam("file") MultipartFile file,
                                                  @RequestParam(value = "poster", required = false) MultipartFile poster,
                                                  @RequestParam(value = "headline", required = false) String headline,
                                                  @RequestParam(value = "body", required = false) String body,
                                                  @RequestParam(value = "productId", required = false) Long productId,
                                                  @RequestParam(value = "ctaLabel", required = false) String ctaLabel,
                                                  @RequestParam(value = "altText", required = false) String altText,
                                                  @RequestParam(value = "durationSeconds", required = false) Integer durationSeconds,
                                                  @RequestParam(value = "width", required = false) Integer width,
                                                  @RequestParam(value = "height", required = false) Integer height) throws IOException {
        return service.addPage(CurrentUser.admin(Storefront.fromPath(storefront)), id, file, poster,
                new WebStoryService.PageInput(headline, body, productId, ctaLabel, altText), durationSeconds, width, height);
    }

    @PutMapping("/{id}/pages/{pageId}")
    @PreAuthorize("hasAuthority('PERM_CATALOG_WRITE')")
    public WebStoryService.AdminStoryView updatePage(@PathVariable String storefront, @PathVariable Long id,
                                                     @PathVariable Long pageId, @RequestBody WebStoryService.PageInput input) {
        return service.updatePage(CurrentUser.admin(Storefront.fromPath(storefront)), id, pageId, input);
    }

    @PostMapping("/{id}/pages/{pageId}/move")
    @PreAuthorize("hasAuthority('PERM_CATALOG_WRITE')")
    public WebStoryService.AdminStoryView movePage(@PathVariable String storefront, @PathVariable Long id,
                                                   @PathVariable Long pageId, @RequestParam int direction) {
        return service.movePage(CurrentUser.admin(Storefront.fromPath(storefront)), id, pageId, direction);
    }

    @DeleteMapping("/{id}/pages/{pageId}")
    @PreAuthorize("hasAuthority('PERM_CATALOG_WRITE')")
    public WebStoryService.AdminStoryView deletePage(@PathVariable String storefront, @PathVariable Long id,
                                                     @PathVariable Long pageId) {
        return service.deletePage(CurrentUser.admin(Storefront.fromPath(storefront)), id, pageId);
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAuthority('PERM_CATALOG_WRITE')")
    public WebStoryService.AdminStoryView publish(@PathVariable String storefront, @PathVariable Long id) {
        return service.publish(CurrentUser.admin(Storefront.fromPath(storefront)), id);
    }

    @PostMapping("/{id}/unpublish")
    @PreAuthorize("hasAuthority('PERM_CATALOG_WRITE')")
    public WebStoryService.AdminStoryView unpublish(@PathVariable String storefront, @PathVariable Long id) {
        return service.unpublish(CurrentUser.admin(Storefront.fromPath(storefront)), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_CATALOG_WRITE')")
    public void delete(@PathVariable String storefront, @PathVariable Long id) {
        service.delete(CurrentUser.admin(Storefront.fromPath(storefront)), id);
    }
}
