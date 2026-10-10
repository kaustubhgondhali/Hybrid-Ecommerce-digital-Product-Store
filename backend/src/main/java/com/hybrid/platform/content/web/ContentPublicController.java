package com.hybrid.platform.content.web;

import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.content.ContentMarkup;
import com.hybrid.platform.content.ContentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/{storefront}/catalog/pages")
public class ContentPublicController {

    public record PublicPage(String slug, String title, String html) {
    }

    private final ContentService service;

    public ContentPublicController(ContentService service) {
        this.service = service;
    }

    @GetMapping
    public List<ContentService.FooterLink> footer(@PathVariable String storefront) {
        return service.footer(Storefront.fromPath(storefront));
    }

    @GetMapping("/{slug}")
    public PublicPage page(@PathVariable String storefront, @PathVariable String slug) {
        var p = service.published(Storefront.fromPath(storefront), slug)
                .orElseThrow(() -> ApiException.notFound("PAGE_NOT_FOUND", "Page not found"));
        return new PublicPage(p.getSlug(), p.getTitle(), ContentMarkup.toHtml(p.getBody()));
    }
}
