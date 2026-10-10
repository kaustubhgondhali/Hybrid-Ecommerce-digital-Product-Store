package com.hybrid.platform.commerce.settings.web;

import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.commerce.settings.SettingsService;
import com.hybrid.platform.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/{storefront}/admin/settings")
public class SettingsAdminController {

    private final SettingsService service;

    public SettingsAdminController(SettingsService service) {
        this.service = service;
    }

    @GetMapping("/store")
    @PreAuthorize("hasAuthority('PERM_DASHBOARD_READ')")
    public SettingsService.StoreSettingsView store(@PathVariable String storefront) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return service.get(sf);
    }

    @PutMapping("/store")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_WRITE')")
    public SettingsService.StoreSettingsView updateStore(@PathVariable String storefront,
                                                         @RequestBody SettingsService.StoreSettingsInput input) {
        return service.update(CurrentUser.admin(Storefront.fromPath(storefront)), input);
    }

    @GetMapping("/shipping-methods")
    @PreAuthorize("hasAuthority('PERM_DASHBOARD_READ')")
    public List<SettingsService.ShippingMethodView> shippingMethods(@PathVariable String storefront) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return service.listShippingMethods(sf);
    }

    @PostMapping("/shipping-methods")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_SETTINGS_WRITE')")
    public SettingsService.ShippingMethodView createShippingMethod(@PathVariable String storefront,
                                                                   @RequestBody SettingsService.ShippingMethodInput input) {
        return service.createShippingMethod(CurrentUser.admin(Storefront.fromPath(storefront)), input);
    }

    @PutMapping("/shipping-methods/{id}")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_WRITE')")
    public SettingsService.ShippingMethodView updateShippingMethod(@PathVariable String storefront, @PathVariable Long id,
                                                                   @RequestBody SettingsService.ShippingMethodInput input) {
        return service.updateShippingMethod(CurrentUser.admin(Storefront.fromPath(storefront)), id, input);
    }
}
