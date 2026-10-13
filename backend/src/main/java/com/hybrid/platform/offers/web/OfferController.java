package com.hybrid.platform.offers.web;

import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.offers.OfferService;
import com.hybrid.platform.security.CurrentUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

/** Coupon offers: admin management, the public list, and the tracked redirect to the store. */
@RestController
public class OfferController {

    private final OfferService service;

    public OfferController(OfferService service) {
        this.service = service;
    }

    @GetMapping("/api/{storefront}/admin/offers")
    @PreAuthorize("hasAuthority('PERM_AFFILIATE_READ')")
    public List<OfferService.AdminOfferView> list(@PathVariable String storefront) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return service.list(sf);
    }

    @PostMapping("/api/{storefront}/admin/offers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_AFFILIATE_WRITE')")
    public OfferService.AdminOfferView create(@PathVariable String storefront, @RequestBody OfferService.OfferInput input) {
        return service.create(CurrentUser.admin(Storefront.fromPath(storefront)), input);
    }

    @PutMapping("/api/{storefront}/admin/offers/{id}")
    @PreAuthorize("hasAuthority('PERM_AFFILIATE_WRITE')")
    public OfferService.AdminOfferView update(@PathVariable String storefront, @PathVariable Long id,
                                              @RequestBody OfferService.OfferInput input) {
        return service.update(CurrentUser.admin(Storefront.fromPath(storefront)), id, input);
    }

    @DeleteMapping("/api/{storefront}/admin/offers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_AFFILIATE_WRITE')")
    public void delete(@PathVariable String storefront, @PathVariable Long id) {
        service.delete(CurrentUser.admin(Storefront.fromPath(storefront)), id);
    }

    /** Live offers, optionally for one store (domain) or one product. */
    @GetMapping("/api/{storefront}/catalog/offers")
    public List<OfferService.PublicOffer> live(@PathVariable String storefront, @RequestParam(required = false) String store,
                                               @RequestParam(required = false) Long product) {
        return service.live(Storefront.fromPath(storefront), store, product);
    }

    @GetMapping("/go/o/{id}")
    public ResponseEntity<Void> go(@PathVariable Long id, @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return service.redirect(id, userAgent)
                .map(url -> ResponseEntity.status(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION, url)
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .header("Referrer-Policy", "no-referrer-when-downgrade")
                        .header("X-Robots-Tag", "noindex, nofollow")
                        .<Void>build())
                .orElseGet(() -> ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/link-unavailable.html")).build());
    }
}
