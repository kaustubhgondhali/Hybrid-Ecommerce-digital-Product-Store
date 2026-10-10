package com.hybrid.platform.secaudit.web;

import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.secaudit.SecurityAdminService;
import com.hybrid.platform.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/{storefront}/admin/security")
public class SecurityAdminController {

    private final SecurityAdminService security;

    public SecurityAdminController(SecurityAdminService security) {
        this.security = security;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_AUDIT_READ')")
    public SecurityAdminService.Report audit(@PathVariable String storefront) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return security.audit(sf);
    }
}
