package com.hybrid.platform.seo.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.seo.HtmlShells;
import com.hybrid.platform.seo.SeoPage;
import com.hybrid.platform.seo.SeoPageService;
import com.hybrid.platform.seo.SitemapService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/** Server-renders SEO tags and crawlable content into the storefront page shells; the JS then enhances the page. */
@Controller
public class SeoPageController {

    private static final MediaType HTML = new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8);

    private final SeoPageService pages;
    private final SitemapService sitemap;
    private final HtmlShells shells;
    private final ObjectMapper mapper;

    public SeoPageController(SeoPageService pages, SitemapService sitemap, HtmlShells shells, ObjectMapper mapper) {
        this.pages = pages;
        this.sitemap = sitemap;
        this.shells = shells;
        this.mapper = mapper;
    }

    @GetMapping({"/{sf:shop|digital}", "/{sf:shop|digital}/"})
    public ResponseEntity<String> home(@PathVariable String sf, @RequestParam Map<String, String> query) {
        return ok("store/index.html", pages.home(Storefront.fromPath(sf), query));
    }

    @GetMapping("/{sf:shop|digital}/c/{slug}")
    public ResponseEntity<String> category(@PathVariable String sf, @PathVariable String slug,
                                           @RequestParam Map<String, String> query, HttpServletRequest request) {
        Storefront s = Storefront.fromPath(sf);
        return render("store/index.html", s, pages.category(s, slug, query), request);
    }

    @GetMapping("/{sf:shop|digital}/p/{slug}")
    public ResponseEntity<String> product(@PathVariable String sf, @PathVariable String slug, HttpServletRequest request) {
        Storefront s = Storefront.fromPath(sf);
        return render("store/product.html", s, pages.product(s, slug), request);
    }

    @GetMapping("/{sf:shop|digital}/page/{slug}")
    public ResponseEntity<String> content(@PathVariable String sf, @PathVariable String slug, HttpServletRequest request) {
        Storefront s = Storefront.fromPath(sf);
        return render("store/page.html", s, pages.contentPage(s, slug), request);
    }

    @GetMapping(value = "/sitemap.xml", produces = "application/xml")
    public ResponseEntity<String> sitemapXml() {
        return ResponseEntity.ok().contentType(new MediaType("application", "xml", java.nio.charset.StandardCharsets.UTF_8))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(sitemap.sitemapXml());
    }

    @GetMapping(value = "/robots.txt", produces = "text/plain")
    public ResponseEntity<String> robots() {
        return ResponseEntity.ok().contentType(new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(sitemap.robotsTxt());
    }

    private ResponseEntity<String> render(String shell, Storefront sf, Optional<SeoPage> page, HttpServletRequest request) {
        if (page.isPresent()) return ok(shell, page.get());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(HTML).cacheControl(CacheControl.noCache())
                .body(pages.notFound(sf).renderInto(shells.load("store/page.html"), mapper));
    }

    private ResponseEntity<String> ok(String shell, SeoPage page) {
        return ResponseEntity.ok().contentType(HTML).cacheControl(CacheControl.noCache())
                .body(page.renderInto(shells.load(shell), mapper));
    }
}
