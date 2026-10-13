package com.hybrid.platform.stories.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.identity.AdminUser;
import com.hybrid.platform.identity.AdminUserRepository;
import com.hybrid.platform.security.AdminPrincipal;
import com.hybrid.platform.security.SessionAuth;
import com.hybrid.platform.seo.HtmlShells;
import com.hybrid.platform.seo.SeoPageService;
import com.hybrid.platform.stories.WebStoryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Public story pages. A story is an AMP document, which loads Google's AMP runtime, so it gets its own
 * Content-Security-Policy; every other page keeps the strict default policy.
 */
@Controller
public class WebStoryPageController {

    private static final MediaType HTML = new MediaType("text", "html", StandardCharsets.UTF_8);

    static final String AMP_CSP = "default-src 'self'; script-src https://cdn.ampproject.org; "
            + "style-src 'unsafe-inline' https://cdn.ampproject.org https://fonts.googleapis.com; img-src 'self' data: blob: https:; "
            + "media-src 'self' blob: https:; font-src 'self' data: https://fonts.gstatic.com https://cdn.ampproject.org; "
            + "connect-src 'self' https://cdn.ampproject.org; frame-src 'self' https://cdn.ampproject.org; worker-src 'self' blob:; "
            + "object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'self'";

    private final WebStoryService stories;
    private final SeoPageService pages;
    private final HtmlShells shells;
    private final ObjectMapper mapper;
    private final AdminUserRepository admins;

    public WebStoryPageController(WebStoryService stories, SeoPageService pages, HtmlShells shells, ObjectMapper mapper,
                                  AdminUserRepository admins) {
        this.stories = stories;
        this.pages = pages;
        this.shells = shells;
        this.mapper = mapper;
        this.admins = admins;
    }

    @GetMapping("/{sf:shop|digital}/stories/{slug}")
    public ResponseEntity<String> story(@PathVariable String sf, @PathVariable String slug, HttpServletRequest request) {
        Storefront s = Storefront.fromPath(sf);
        Optional<WebStoryService.Rendered> r = stories.render(s, slug, isStaff(request, s));
        if (r.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(HTML).cacheControl(CacheControl.noCache())
                    .body(pages.notFound(s).renderInto(shells.load("store/page.html"), mapper));
        }
        return ResponseEntity.ok().contentType(HTML).header("Content-Security-Policy", AMP_CSP)
                .cacheControl(r.get().preview() ? CacheControl.noStore() : CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(r.get().html());
    }

    @GetMapping({"/{sf:shop|digital}/stories", "/{sf:shop|digital}/stories/"})
    public ResponseEntity<String> index(@PathVariable String sf) {
        return ResponseEntity.ok().contentType(HTML).cacheControl(CacheControl.noCache())
                .body(pages.storiesIndex(Storefront.fromPath(sf)).renderInto(shells.load("store/page.html"), mapper));
    }

    /** Published stories for the storefront strip (newest first). */
    @GetMapping("/api/{sf:shop|digital}/catalog/stories")
    @ResponseBody
    public ResponseEntity<List<WebStoryService.StoryCard>> cards(@PathVariable String sf,
                                                                 @RequestParam(defaultValue = "12") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(2)).cachePublic())
                .body(stories.cards(Storefront.fromPath(sf), limit));
    }

    /** Signed in to this storefront's admin with catalog access: may preview unpublished stories. */
    private boolean isStaff(HttpServletRequest request, Storefront sf) {
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(SessionAuth.adminKey(sf)) instanceof SecurityContext ctx)) return false;
        if (ctx.getAuthentication() == null || !(ctx.getAuthentication().getPrincipal() instanceof AdminPrincipal admin)) return false;
        boolean canRead = ctx.getAuthentication().getAuthorities().stream().anyMatch(a -> "PERM_CATALOG_READ".equals(a.getAuthority()));
        return canRead && admin.storefront() == sf
                && admins.findById(admin.id()).map(u -> u.getStatus() == AdminUser.Status.ACTIVE).orElse(false);
    }
}
