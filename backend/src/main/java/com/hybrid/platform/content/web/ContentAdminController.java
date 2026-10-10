package com.hybrid.platform.content.web;

import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.content.ContentService;
import com.hybrid.platform.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/{storefront}/admin/content-pages")
public class ContentAdminController {

    private final ContentService service;

    public ContentAdminController(ContentService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_DASHBOARD_READ')")
    public List<ContentService.PageView> list(@PathVariable String storefront) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return service.list(sf);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_DASHBOARD_READ')")
    public ContentService.PageView get(@PathVariable String storefront, @PathVariable Long id) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return service.get(sf, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_SETTINGS_WRITE')")
    public ContentService.PageView create(@PathVariable String storefront, @RequestBody ContentService.PageInput input) {
        return service.create(CurrentUser.admin(Storefront.fromPath(storefront)), input);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_WRITE')")
    public ContentService.PageView update(@PathVariable String storefront, @PathVariable Long id,
                                          @RequestBody ContentService.PageInput input) {
        return service.update(CurrentUser.admin(Storefront.fromPath(storefront)), id, input);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_SETTINGS_WRITE')")
    public void delete(@PathVariable String storefront, @PathVariable Long id) {
        service.delete(CurrentUser.admin(Storefront.fromPath(storefront)), id);
    }
}
