package com.hybrid.platform.identity.web;

import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.identity.Customer;
import com.hybrid.platform.identity.CustomerAuthService;
import com.hybrid.platform.security.CurrentUser;
import com.hybrid.platform.security.CustomerPrincipal;
import com.hybrid.platform.security.SessionAuth;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class CustomerAuthController {

    public record RegisterRequest(@NotBlank @Email @Size(max = 254) String email,
                                  @NotBlank @Size(max = 128) String password,
                                  @NotBlank @Size(max = 120) String fullName,
                                  String storefront) {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record TokenRequest(@NotBlank String token) {
    }

    public record ResetRequest(@NotBlank @Email String email, String storefront) {
    }

    public record ResetConfirmRequest(@NotBlank String token, @NotBlank String newPassword) {
    }

    public record ProfileRequest(@NotBlank @Size(max = 120) String fullName, @Size(max = 30) String phone) {
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {
    }

    public record Me(Long id, String email, String fullName, String phone, boolean emailVerified) {
        static Me of(Customer c) {
            return new Me(c.getId(), c.getEmail(), c.getFullName(), c.getPhone(), c.isEmailVerified());
        }
    }

    private final CustomerAuthService service;
    private final SessionAuth sessionAuth;

    public CustomerAuthController(CustomerAuthService service, SessionAuth sessionAuth) {
        this.service = service;
        this.sessionAuth = sessionAuth;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Me register(@Valid @RequestBody RegisterRequest req, HttpServletRequest http) {
        CustomerPrincipal p = service.register(req.email(), req.password(), req.fullName(), storefrontHint(req.storefront()));
        sessionAuth.loginCustomer(http, p);
        return Me.of(service.get(p.id()));
    }

    @PostMapping("/login")
    public Me login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        CustomerPrincipal p = service.login(req.email(), req.password());
        sessionAuth.loginCustomer(http, p);
        return Me.of(service.get(p.id()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest http) {
        sessionAuth.logoutCustomer(http);
    }

    @GetMapping("/me")
    public Me me() {
        return Me.of(service.get(CurrentUser.customer().id()));
    }

    @PutMapping("/me")
    public Me updateProfile(@Valid @RequestBody ProfileRequest req) {
        return Me.of(service.updateProfile(CurrentUser.customer().id(), req.fullName(), req.phone()));
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        service.changePassword(CurrentUser.customer().id(), req.currentPassword(), req.newPassword());
    }

    @PostMapping("/verify-email")
    public Map<String, Boolean> verifyEmail(@Valid @RequestBody TokenRequest req) {
        service.verifyEmail(req.token());
        return Map.of("verified", true);
    }

    @PostMapping("/verify-email/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resendVerification(@RequestParam(required = false) String storefront) {
        service.resendVerification(CurrentUser.customer().id(), storefrontHint(storefront));
    }

    @PostMapping("/password-reset/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void requestReset(@Valid @RequestBody ResetRequest req) {
        service.requestPasswordReset(req.email(), storefrontHint(req.storefront()));
    }

    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmReset(@Valid @RequestBody ResetConfirmRequest req) {
        service.confirmPasswordReset(req.token(), req.newPassword());
    }

    private static Storefront storefrontHint(String value) {
        try {
            return value == null ? Storefront.SHOP : Storefront.fromPath(value);
        } catch (RuntimeException e) {
            return Storefront.SHOP;
        }
    }
}
