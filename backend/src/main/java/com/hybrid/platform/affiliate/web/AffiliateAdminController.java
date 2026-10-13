package com.hybrid.platform.affiliate.web;

import com.hybrid.platform.affiliate.AffiliateConversion;
import com.hybrid.platform.affiliate.AffiliateService;
import com.hybrid.platform.common.PageResponse;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/{storefront}/admin")
public class AffiliateAdminController {

    public record SetLinkRequest(@NotBlank @Size(max = 2048) String url, Long displayPriceMinor, Long listPriceMinor,
                                 @Size(max = 500) String disclosure, @Size(max = 120) String merchantName,
                                 boolean confirmNewDomain) {
        AffiliateService.SetLinkCommand toCommand() {
            return new AffiliateService.SetLinkCommand(url, displayPriceMinor, listPriceMinor, disclosure, merchantName, confirmNewDomain);
        }
    }

    public record ActiveRequest(boolean active) {
    }

    public record MerchantUpdate(@Size(max = 120) String name, @Size(max = 40) String subIdParam, Boolean allowHttp,
                                 Boolean active, Boolean hidePrices) {
    }

    public record ConversionUpdate(AffiliateConversion.Status status, @Size(max = 300) String note) {
    }

    public record ImportRequest(@NotBlank @Size(max = 2_000_000) String csv) {
    }

    private final AffiliateService service;

    public AffiliateAdminController(AffiliateService service) {
        this.service = service;
    }

    @PutMapping("/products/{productId}/affiliate-link")
    @PreAuthorize("hasAuthority('PERM_AFFILIATE_WRITE')")
    public AffiliateService.LinkView setLink(@PathVariable String storefront, @PathVariable Long productId,
                                             @Valid @RequestBody SetLinkRequest req) {
        return service.setLink(CurrentUser.admin(Storefront.fromPath(storefront)), productId, req.toCommand());
    }

    @PostMapping("/products/{productId}/affiliate-link/confirm-price")
    @PreAuthorize("hasAuthority('PERM_AFFILIATE_WRITE')")
    public AffiliateService.LinkView confirmPrice(@PathVariable String storefront, @PathVariable Long productId) {
        return service.confirmPrice(CurrentUser.admin(Storefront.fromPath(storefront)), productId);
    }

    // ---- several partner stores per product

    @GetMapping("/products/{productId}/affiliate-links")
    @PreAuthorize("hasAuthority('PERM_AFFILIATE_READ')")
    public List<AffiliateService.LinkView> productLinks(@PathVariable String storefront, @PathVariable Long productId) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return service.adminLinks(sf, productId);
    }

    @PostMapping("/products/{productId}/affiliate-links")
    @PreAuthorize("hasAuthority('PERM_AFFILIATE_WRITE')")
    public AffiliateService.LinkView addLink(@PathVariable String storefront, @PathVariable Long productId,
                                             @Valid @RequestBody SetLinkRequest req) {
        return service.setLink(CurrentUser.admin(Storefront.fromPath(storefront)), productId, req.toCommand());
    }

    @PutMapping("/products/{productId}/affiliate-links/{linkId}")
    @PreAuthorize("hasAuthority('PERM_AFFILIATE_WRITE')")
    public AffiliateService.LinkView updateLink(@PathVariable String storefront, @PathVariable Long productId,
                                                @PathVariable Long linkId, @Valid @RequestBody SetLinkRequest req) {
        return service.updateLink(CurrentUser.admin(Storefront.fromPath(storefront)), productId, linkId, req.toCommand());
    }

    @PatchMapping("/products/{productId}/affiliate-links/{linkId}")
    @PreAuthorize("hasAuthority('PERM_AFFILIATE_WRITE')")
    public AffiliateService.LinkView setLinkActive(@PathVariable String storefront, @PathVariable Long productId,
                                                   @PathVariable Long linkId, @RequestBody ActiveRequest req) {
        return service.setLinkActive(CurrentUser.admin(Storefront.fromPath(storefront)), productId, linkId, req.active());
    }

    @PostMapping("/products/{productId}/affiliate-links/{linkId}/confirm-price")
    @PreAuthorize("hasAuthority('PERM_AFFILIATE_WRITE')")
    public AffiliateService.LinkView confirmLinkPrice(@PathVariable String storefront, @PathVariable Long productId,
                                                      @PathVariable Long linkId) {
        return service.confirmPrice(CurrentUser.admin(Storefront.fromPath(storefront)), productId, linkId);
    }

    @GetMapping("/affiliate/links")
    @PreAuthorize("hasAuthority('PERM_AFFILIATE_READ')")
    public List<AffiliateService.LinkView> links(@PathVariable String storefront) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return service.listLinks(sf);
    }

    @GetMapping("/affiliate/merchants")
    @PreAuthorize("hasAuthority('PERM_AFFILIATE_READ')")
    public List<AffiliateService.MerchantView> merchants(@PathVariable String storefront) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return service.listMerchants(sf);
    }

    @PatchMapping("/affiliate/merchants/{id}")
    @PreAuthorize("hasAuthority('PERM_AFFILIATE_WRITE')")
    public AffiliateService.MerchantView updateMerchant(@PathVariable String storefront, @PathVariable Long id,
                                                        @Valid @RequestBody MerchantUpdate req) {
        return service.updateMerchant(CurrentUser.admin(Storefront.fromPath(storefront)), id, req.name(),
                req.subIdParam(), req.allowHttp(), req.active(), req.hidePrices());
    }

    @GetMapping("/affiliate/conversions")
    @PreAuthorize("hasAuthority('PERM_AFFILIATE_READ')")
    public PageResponse<AffiliateService.ConversionView> conversions(@PathVariable String storefront,
                                                                     @RequestParam(defaultValue = "0") int page,
                                                                     @RequestParam(defaultValue = "50") int size) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return service.listConversions(sf, page, size);
    }

    @PatchMapping("/affiliate/conversions/{id}")
    @PreAuthorize("hasAuthority('PERM_AFFILIATE_WRITE')")
    public AffiliateService.ConversionView updateConversion(@PathVariable String storefront, @PathVariable Long id,
                                                            @Valid @RequestBody ConversionUpdate req) {
        return service.updateConversion(CurrentUser.admin(Storefront.fromPath(storefront)), id, req.status(), req.note());
    }

    @PostMapping("/affiliate/conversions/import")
    @PreAuthorize("hasAuthority('PERM_AFFILIATE_WRITE')")
    public AffiliateService.ImportResult importConversions(@PathVariable String storefront, @Valid @RequestBody ImportRequest req) {
        return service.importConversions(CurrentUser.admin(Storefront.fromPath(storefront)), req.csv());
    }

    @GetMapping("/affiliate/report")
    @PreAuthorize("hasAuthority('PERM_AFFILIATE_READ')")
    public AffiliateService.Report report(@PathVariable String storefront) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return service.report(sf);
    }
}
