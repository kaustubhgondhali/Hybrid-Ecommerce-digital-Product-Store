package com.hybrid.platform.delivery.web;

import com.hybrid.platform.common.PageResponse;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.delivery.DeliveryService;
import com.hybrid.platform.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/{storefront}/admin")
public class DeliveryAdminController {

    public record PolicyRequest(Integer downloadLimit, Integer accessDays) {
    }

    public record KeysRequest(@NotBlank @Size(max = 5_000_000) String keys) {
    }

    public record GrantRequest(@NotBlank @Email String customerEmail, @NotNull Long productId,
                               @NotBlank @Size(max = 300) String reason) {
    }

    public record RevokeRequest(@Size(max = 300) String reason) {
    }

    private final DeliveryService service;

    public DeliveryAdminController(DeliveryService service) {
        this.service = service;
    }

    @GetMapping("/products/{productId}/delivery")
    @PreAuthorize("hasAuthority('PERM_CATALOG_READ')")
    public DeliveryService.PolicyView delivery(@PathVariable String storefront, @PathVariable Long productId) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return service.policy(sf, productId);
    }

    @PutMapping("/products/{productId}/delivery")
    @PreAuthorize("hasAuthority('PERM_DIGITAL_WRITE')")
    public DeliveryService.PolicyView updatePolicy(@PathVariable String storefront, @PathVariable Long productId,
                                                   @RequestBody PolicyRequest req) {
        return service.updatePolicy(CurrentUser.admin(Storefront.fromPath(storefront)), productId, req.downloadLimit(), req.accessDays());
    }

    @PostMapping(value = "/products/{productId}/assets", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_DIGITAL_WRITE')")
    public DeliveryService.AssetView upload(@PathVariable String storefront, @PathVariable Long productId,
                                            @RequestParam("file") MultipartFile file,
                                            @RequestParam(value = "versionLabel", required = false) String versionLabel) throws IOException {
        return service.uploadAsset(CurrentUser.admin(Storefront.fromPath(storefront)), productId, file, versionLabel);
    }

    @DeleteMapping("/assets/{assetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_DIGITAL_WRITE')")
    public void retire(@PathVariable String storefront, @PathVariable Long assetId) {
        service.retireAsset(CurrentUser.admin(Storefront.fromPath(storefront)), assetId);
    }

    @PostMapping("/products/{productId}/license-keys")
    @PreAuthorize("hasAuthority('PERM_DIGITAL_WRITE')")
    public DeliveryService.ImportKeysResult importKeys(@PathVariable String storefront, @PathVariable Long productId,
                                                      @Valid @RequestBody KeysRequest req) {
        return service.importLicenseKeys(CurrentUser.admin(Storefront.fromPath(storefront)), productId, req.keys());
    }

    @GetMapping("/entitlements")
    @PreAuthorize("hasAuthority('PERM_ORDERS_READ')")
    public PageResponse<DeliveryService.EntitlementAdminView> entitlements(@PathVariable String storefront,
                                                                           @RequestParam(required = false) String customerEmail,
                                                                           @RequestParam(defaultValue = "0") int page,
                                                                           @RequestParam(defaultValue = "50") int size) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return service.listEntitlements(sf, customerEmail, page, size);
    }

    @PostMapping("/entitlements")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_ORDERS_WRITE')")
    public void grant(@PathVariable String storefront, @Valid @RequestBody GrantRequest req) {
        service.grantManually(CurrentUser.admin(Storefront.fromPath(storefront)), req.customerEmail(), req.productId(), req.reason());
    }

    @PostMapping("/entitlements/{id}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_ORDERS_WRITE')")
    public void revoke(@PathVariable String storefront, @PathVariable Long id, @Valid @RequestBody RevokeRequest req) {
        service.revoke(CurrentUser.admin(Storefront.fromPath(storefront)), id, req.reason());
    }
}
