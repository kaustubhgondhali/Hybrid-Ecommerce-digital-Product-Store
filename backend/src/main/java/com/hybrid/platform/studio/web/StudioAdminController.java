package com.hybrid.platform.studio.web;

import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.security.AdminPrincipal;
import com.hybrid.platform.security.CurrentUser;
import com.hybrid.platform.studio.ContentStudioService;
import com.hybrid.platform.studio.ContentWriter;
import com.hybrid.platform.studio.PosterRenderer;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Content studio: generated blog posts, stories, newsletters, social posts and posters for published products. */
@RestController
@RequestMapping("/api/{storefront}/admin/studio")
public class StudioAdminController {

    public record MultiInput(List<Long> productIds, String topic, Integer variant) {
    }

    public record BlogInput(Long productId, List<Long> productIds, String kind, String topic, Integer variant, Boolean publish) {
    }

    public record StoryInput(Long productId, Integer variant, Boolean publish) {
    }

    public record NewsletterInput(List<Long> productIds, String kind, String topic, Integer variant) {
    }

    private final ContentStudioService studio;

    public StudioAdminController(ContentStudioService studio) {
        this.studio = studio;
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('PERM_CATALOG_READ')")
    public ContentStudioService.SettingsView settings(@PathVariable String storefront) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return studio.settings(sf);
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('PERM_CATALOG_WRITE')")
    public ContentStudioService.SettingsView updateSettings(@PathVariable String storefront, @RequestBody ContentStudioService.SettingsView in) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return studio.updateSettings(sf, in);
    }

    @GetMapping("/products/{id}")
    @PreAuthorize("hasAuthority('PERM_CATALOG_READ')")
    public ContentStudioService.ProductPreview preview(@PathVariable String storefront, @PathVariable Long id,
                                                       @RequestParam(defaultValue = "0") int variant) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return studio.preview(sf, id, variant);
    }

    @PostMapping("/collections/preview")
    @PreAuthorize("hasAuthority('PERM_CATALOG_READ')")
    public ContentStudioService.MultiPreview previewMany(@PathVariable String storefront, @RequestBody MultiInput in) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return studio.previewMany(sf, in.productIds(), in.topic(), variant(in.variant()));
    }

    @PostMapping("/blogs")
    @PreAuthorize("hasAuthority('PERM_CATALOG_WRITE')")
    @ResponseStatus(HttpStatus.CREATED)
    public ContentStudioService.Created blog(@PathVariable String storefront, @RequestBody BlogInput in) {
        AdminPrincipal admin = CurrentUser.admin(Storefront.fromPath(storefront));
        ContentWriter.BlogKind kind = parse(ContentWriter.BlogKind.class, in.kind(), ContentWriter.BlogKind.REVIEW);
        boolean publish = Boolean.TRUE.equals(in.publish());
        if (kind == ContentWriter.BlogKind.COMPARISON || kind == ContentWriter.BlogKind.ROUNDUP) {
            return studio.createMultiBlog(admin, in.productIds(), kind, in.topic(), variant(in.variant()), publish);
        }
        if (in.productId() == null) throw ApiException.badRequest("CHOOSE_PRODUCT", "Choose a product");
        return studio.createBlog(admin, in.productId(), kind, variant(in.variant()), publish, false);
    }

    @PostMapping("/stories")
    @PreAuthorize("hasAuthority('PERM_CATALOG_WRITE')")
    @ResponseStatus(HttpStatus.CREATED)
    public ContentStudioService.Created story(@PathVariable String storefront, @RequestBody StoryInput in) {
        AdminPrincipal admin = CurrentUser.admin(Storefront.fromPath(storefront));
        if (in.productId() == null) throw ApiException.badRequest("CHOOSE_PRODUCT", "Choose a product");
        return studio.createStory(admin, in.productId(), variant(in.variant()), Boolean.TRUE.equals(in.publish()), false);
    }

    @PostMapping("/newsletters")
    @PreAuthorize("hasAuthority('PERM_MARKETING_SEND')")
    @ResponseStatus(HttpStatus.CREATED)
    public ContentStudioService.Created newsletter(@PathVariable String storefront, @RequestBody NewsletterInput in) {
        AdminPrincipal admin = CurrentUser.admin(Storefront.fromPath(storefront));
        ContentWriter.EmailKind kind = parse(ContentWriter.EmailKind.class, in.kind(), ContentWriter.EmailKind.LAUNCH);
        return studio.createNewsletter(admin, in.productIds(), kind, in.topic(), variant(in.variant()), false);
    }

    @GetMapping("/posters/{id}")
    @PreAuthorize("hasAuthority('PERM_CATALOG_READ')")
    public ResponseEntity<byte[]> poster(@PathVariable String storefront, @PathVariable Long id,
                                         @RequestParam(defaultValue = "SQUARE") String format,
                                         @RequestParam(defaultValue = "LIGHT") String theme) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        PosterRenderer.Format f = parse(PosterRenderer.Format.class, format, PosterRenderer.Format.SQUARE);
        PosterRenderer.Theme t = parse(PosterRenderer.Theme.class, theme, PosterRenderer.Theme.LIGHT);
        return png(studio.poster(sf, id, f, t), "product-" + id + "-" + f.name().toLowerCase(Locale.ROOT) + ".png");
    }

    @GetMapping("/posters/collection")
    @PreAuthorize("hasAuthority('PERM_CATALOG_READ')")
    public ResponseEntity<byte[]> collectionPoster(@PathVariable String storefront, @RequestParam String ids,
                                                   @RequestParam(required = false) String headline,
                                                   @RequestParam(required = false) String subline,
                                                   @RequestParam(defaultValue = "SQUARE") String format,
                                                   @RequestParam(defaultValue = "SALE") String theme) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        List<Long> list;
        try {
            list = Arrays.stream(ids.split(",")).map(String::strip).filter(s -> !s.isEmpty()).map(Long::valueOf).toList();
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("CHOOSE_PRODUCTS", "Choose products for the banner");
        }
        PosterRenderer.Format f = parse(PosterRenderer.Format.class, format, PosterRenderer.Format.SQUARE);
        PosterRenderer.Theme t = parse(PosterRenderer.Theme.class, theme, PosterRenderer.Theme.SALE);
        return png(studio.collectionPoster(sf, list, headline, subline, f, t), "collection-" + f.name().toLowerCase(Locale.ROOT) + ".png");
    }

    @GetMapping("/history")
    @PreAuthorize("hasAuthority('PERM_CATALOG_READ')")
    public List<ContentStudioService.LogEntry> history(@PathVariable String storefront, @RequestParam(defaultValue = "50") int limit) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return studio.history(sf, limit);
    }

    private static ResponseEntity<byte[]> png(byte[] body, String filename) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore())
                .header("Content-Disposition", "inline; filename=\"" + filename + "\"").body(body);
    }

    private static int variant(Integer v) {
        return v == null ? 0 : Math.floorMod(v, 1000);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String v, E fallback) {
        if (v == null || v.isBlank()) return fallback;
        try {
            return Enum.valueOf(type, v.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_VALUE", "Unknown option: " + v);
        }
    }
}
