package com.hybrid.platform.identity.web;

import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.identity.AdminAuthService;
import com.hybrid.platform.identity.AdminUser;
import com.hybrid.platform.identity.Permission;
import com.hybrid.platform.security.AdminPrincipal;
import com.hybrid.platform.security.CurrentUser;
import com.hybrid.platform.security.SessionAuth;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/{storefront}/admin/auth")
public class AdminAuthController {

    public record LoginRequest(@NotBlank String email, @NotBlank String password, String totpCode) {
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {
    }

    public record CodeRequest(@NotBlank String code) {
    }

    public record AdminMe(Long id, String storefront, String email, String fullName, String role,
                          List<String> permissions, boolean mustChangePassword, boolean mfaEnabled) {
        static AdminMe of(AdminUser a) {
            return new AdminMe(a.getId(), a.getStorefront().name(), a.getEmail(), a.getFullName(), a.getRole().name(),
                    a.getRole().permissions().stream().map(Permission::name).sorted().toList(),
                    a.isMustChangePassword(), a.isMfaEnabled());
        }
    }

    private final AdminAuthService service;
    private final SessionAuth sessionAuth;

    public AdminAuthController(AdminAuthService service, SessionAuth sessionAuth) {
        this.service = service;
        this.sessionAuth = sessionAuth;
    }

    @PostMapping("/login")
    public AdminMe login(@PathVariable String storefront, @Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        Storefront sf = Storefront.fromPath(storefront);
        AdminPrincipal p = service.login(sf, req.email(), req.password(), req.totpCode());
        sessionAuth.loginAdmin(http, p);
        return AdminMe.of(service.get(p));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@PathVariable String storefront, HttpServletRequest http) {
        sessionAuth.logoutAdmin(http, Storefront.fromPath(storefront));
    }

    @GetMapping("/me")
    public AdminMe me(@PathVariable String storefront) {
        return AdminMe.of(service.get(CurrentUser.admin(Storefront.fromPath(storefront))));
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@PathVariable String storefront, @Valid @RequestBody ChangePasswordRequest req) {
        service.changePassword(CurrentUser.admin(Storefront.fromPath(storefront)), req.currentPassword(), req.newPassword());
    }

    @PostMapping("/mfa/setup")
    public AdminAuthService.MfaSetup mfaSetup(@PathVariable String storefront) {
        return service.startMfaSetup(CurrentUser.admin(Storefront.fromPath(storefront)));
    }

    @PostMapping("/mfa/enable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void mfaEnable(@PathVariable String storefront, @Valid @RequestBody CodeRequest req) {
        service.enableMfa(CurrentUser.admin(Storefront.fromPath(storefront)), req.code());
    }

    @PostMapping("/mfa/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void mfaDisable(@PathVariable String storefront, @Valid @RequestBody CodeRequest req) {
        service.disableMfa(CurrentUser.admin(Storefront.fromPath(storefront)), req.code());
    }
}
