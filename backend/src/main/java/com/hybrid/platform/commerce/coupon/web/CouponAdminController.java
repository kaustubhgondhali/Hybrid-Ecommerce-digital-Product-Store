package com.hybrid.platform.commerce.coupon.web;

import com.hybrid.platform.commerce.coupon.CouponService;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/{storefront}/admin/coupons")
public class CouponAdminController {

    private final CouponService service;

    public CouponAdminController(CouponService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_ORDERS_READ')")
    public List<CouponService.CouponView> list(@PathVariable String storefront) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return service.list(sf);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_SETTINGS_WRITE')")
    public CouponService.CouponView create(@PathVariable String storefront, @RequestBody CouponService.CouponInput input) {
        return service.create(CurrentUser.admin(Storefront.fromPath(storefront)), input);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_WRITE')")
    public CouponService.CouponView update(@PathVariable String storefront, @PathVariable Long id,
                                           @RequestBody CouponService.CouponInput input) {
        return service.update(CurrentUser.admin(Storefront.fromPath(storefront)), id, input);
    }
}
