package com.hybrid.platform.identity.web;

import com.hybrid.platform.common.PageResponse;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.audit.AuditService;
import com.hybrid.platform.identity.AdminAuthService;
import com.hybrid.platform.identity.AdminRole;
import com.hybrid.platform.identity.AdminUser;
import com.hybrid.platform.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/{storefront}/admin")
public class StaffAdminController {

    public record StaffView(Long id, String email, String fullName, String role, String status, boolean mfaEnabled,
                            boolean mustChangePassword, Instant lastLoginAt, Instant createdAt) {
        static StaffView of(AdminUser a) {
            return new StaffView(a.getId(), a.getEmail(), a.getFullName(), a.getRole().name(), a.getStatus().name(),
                    a.isMfaEnabled(), a.isMustChangePassword(), a.getLastLoginAt(), a.getCreatedAt());
        }
    }

    public record CreateStaffRequest(@NotBlank @Email @Size(max = 254) String email,
                                     @NotBlank @Size(max = 120) String fullName,
                                     @NotNull AdminRole role) {
    }

    public record UpdateStaffRequest(AdminRole role, AdminUser.Status status, @Size(max = 120) String fullName) {
    }

    public record CreatedStaffView(StaffView staff, String temporaryPassword) {
    }

    private final AdminAuthService service;
    private final AuditService audit;

    public StaffAdminController(AdminAuthService service, AuditService audit) {
        this.service = service;
        this.audit = audit;
    }

    @GetMapping("/staff")
    @PreAuthorize("hasAuthority('PERM_STAFF_MANAGE')")
    public List<StaffView> list(@PathVariable String storefront) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return service.listStaff(sf).stream().map(StaffView::of).toList();
    }

    @PostMapping("/staff")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_STAFF_MANAGE')")
    public CreatedStaffView create(@PathVariable String storefront, @Valid @RequestBody CreateStaffRequest req) {
        var created = service.createStaff(CurrentUser.admin(Storefront.fromPath(storefront)), req.email(), req.fullName(), req.role());
        return new CreatedStaffView(StaffView.of(created.user()), created.temporaryPassword());
    }

    @PatchMapping("/staff/{id}")
    @PreAuthorize("hasAuthority('PERM_STAFF_MANAGE')")
    public StaffView update(@PathVariable String storefront, @PathVariable Long id, @Valid @RequestBody UpdateStaffRequest req) {
        return StaffView.of(service.updateStaff(CurrentUser.admin(Storefront.fromPath(storefront)), id, req.role(),
                req.status(), req.fullName()));
    }

    @PostMapping("/staff/{id}/reset-password")
    @PreAuthorize("hasAuthority('PERM_STAFF_MANAGE')")
    public Map<String, String> resetPassword(@PathVariable String storefront, @PathVariable Long id) {
        return Map.of("temporaryPassword", service.resetStaffPassword(CurrentUser.admin(Storefront.fromPath(storefront)), id));
    }

    @GetMapping("/audit-log")
    @PreAuthorize("hasAuthority('PERM_AUDIT_READ')")
    public PageResponse<AuditService.AuditEntry> auditLog(@PathVariable String storefront,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "50") int size) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return audit.list(sf, page, size);
    }
}
