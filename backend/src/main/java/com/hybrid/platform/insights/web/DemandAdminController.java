package com.hybrid.platform.insights.web;

import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.identity.Permission;
import com.hybrid.platform.insights.DemandService;
import com.hybrid.platform.security.AdminPrincipal;
import com.hybrid.platform.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Admin: Sales &amp; demand research for one storefront. Earnings are shown only to staff who can see affiliate data. */
@RestController
@RequestMapping("/api/{storefront}/admin/insights")
public class DemandAdminController {

    private final DemandService service;

    public DemandAdminController(DemandService service) {
        this.service = service;
    }

    @GetMapping("/demand")
    @PreAuthorize("hasAuthority('PERM_DASHBOARD_READ')")
    public DemandService.Report demand(@PathVariable String storefront, @RequestParam(required = false) String period) {
        Storefront sf = Storefront.fromPath(storefront);
        AdminPrincipal admin = CurrentUser.admin(sf);
        return service.report(sf, DemandService.Period.parse(period), admin.role().permissions().contains(Permission.AFFILIATE_READ));
    }
}
