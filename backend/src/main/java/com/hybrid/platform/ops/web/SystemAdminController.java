package com.hybrid.platform.ops.web;

import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.ops.SystemHealthService;
import com.hybrid.platform.security.CurrentUser;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/{storefront}/admin/system")
public class SystemAdminController {

    public record ResolveRequest(@NotNull Boolean resolved) {
    }

    private final SystemHealthService health;

    public SystemAdminController(SystemHealthService health) {
        this.health = health;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_AUDIT_READ')")
    public SystemHealthService.Health get(@PathVariable String storefront,
                                          @RequestParam(defaultValue = "false") boolean resolved) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return health.health(sf, resolved);
    }

    @PatchMapping("/errors/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_AUDIT_READ')")
    public void resolve(@PathVariable String storefront, @PathVariable Long id, @RequestBody ResolveRequest req) {
        health.setResolved(CurrentUser.admin(Storefront.fromPath(storefront)), id, req.resolved());
    }
}
