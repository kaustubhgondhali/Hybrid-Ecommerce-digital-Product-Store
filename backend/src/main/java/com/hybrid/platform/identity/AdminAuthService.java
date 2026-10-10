package com.hybrid.platform.identity;

import com.hybrid.platform.common.AppProperties;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.audit.AuditService;
import com.hybrid.platform.common.crypto.CryptoService;
import com.hybrid.platform.common.crypto.Hashing;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.security.AdminPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class AdminAuthService {

    public record MfaSetup(String secret, String otpauthUri) {
    }

    public record CreatedStaff(AdminUser user, String temporaryPassword) {
    }

    private final AdminUserRepository admins;
    private final PasswordEncoder passwordEncoder;
    private final CryptoService crypto;
    private final AuditService audit;
    private final AppProperties props;
    private final String dummyHash;

    public AdminAuthService(AdminUserRepository admins, PasswordEncoder passwordEncoder, CryptoService crypto,
                            AuditService audit, AppProperties props) {
        this.admins = admins;
        this.passwordEncoder = passwordEncoder;
        this.crypto = crypto;
        this.audit = audit;
        this.props = props;
        this.dummyHash = passwordEncoder.encode(Hashing.randomToken());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public AdminPrincipal login(Storefront sf, String email, String password, String totpCode) {
        Optional<AdminUser> found = admins.findByStorefrontAndEmailIgnoreCase(sf, CustomerAuthService.normalizeEmail(email));
        if (found.isEmpty()) {
            passwordEncoder.matches(password, dummyHash);
            throw invalid();
        }
        AdminUser admin = found.get();
        if (admin.isLocked(Instant.now()) || admin.getStatus() != AdminUser.Status.ACTIVE) {
            throw invalid();
        }
        if (!passwordEncoder.matches(password, admin.getPasswordHash())) {
            admin.recordFailure(props.security().maxFailedLogins(), props.security().lockMinutes());
            throw invalid();
        }
        if (admin.isMfaEnabled()) {
            if (totpCode == null || totpCode.isBlank()) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "MFA_REQUIRED", "Enter the 6-digit code from your authenticator app");
            }
            if (!Totp.verify(crypto.decrypt(admin.getMfaSecretEnc()), totpCode.trim(), Instant.now())) {
                admin.recordFailure(props.security().maxFailedLogins(), props.security().lockMinutes());
                throw new ApiException(HttpStatus.UNAUTHORIZED, "MFA_INVALID", "The authenticator code is incorrect");
            }
        }
        admin.recordSuccess();
        return toPrincipal(admin);
    }

    @Transactional(readOnly = true)
    public AdminUser get(AdminPrincipal principal) {
        return admins.findByIdAndStorefront(principal.id(), principal.storefront())
                .orElseThrow(() -> ApiException.unauthorized("UNAUTHENTICATED", "Please sign in"));
    }

    @Transactional
    public void changePassword(AdminPrincipal principal, String currentPassword, String newPassword) {
        AdminUser admin = get(principal);
        if (!passwordEncoder.matches(currentPassword, admin.getPasswordHash())) {
            throw ApiException.rule("CURRENT_PASSWORD_WRONG", "Current password is incorrect");
        }
        PasswordPolicy.check(newPassword);
        if (passwordEncoder.matches(newPassword, admin.getPasswordHash())) {
            throw ApiException.rule("PASSWORD_REUSED", "Choose a different password from the current one");
        }
        admin.setPasswordHash(passwordEncoder.encode(newPassword));
        admin.setMustChangePassword(false);
        audit.record(principal, "ADMIN_PASSWORD_CHANGED", "AdminUser", admin.getId(), null);
    }

    @Transactional
    public MfaSetup startMfaSetup(AdminPrincipal principal) {
        AdminUser admin = get(principal);
        if (admin.isMfaEnabled()) {
            throw ApiException.conflict("MFA_ALREADY_ENABLED", "Two-factor authentication is already enabled");
        }
        String secret = Totp.base32Encode(Hashing.randomBytes(20));
        admin.setMfaSecretEnc(crypto.encrypt(secret));
        String label = URLEncoder.encode("HybridPlatform " + admin.getStorefront().name() + ":" + admin.getEmail(), StandardCharsets.UTF_8);
        String issuer = URLEncoder.encode("HybridPlatform-" + admin.getStorefront().name(), StandardCharsets.UTF_8);
        return new MfaSetup(secret, "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + issuer);
    }

    @Transactional
    public void enableMfa(AdminPrincipal principal, String code) {
        AdminUser admin = get(principal);
        if (admin.getMfaSecretEnc() == null) {
            throw ApiException.rule("MFA_NOT_STARTED", "Start the two-factor setup first");
        }
        if (!Totp.verify(crypto.decrypt(admin.getMfaSecretEnc()), code, Instant.now())) {
            throw ApiException.rule("MFA_INVALID", "The authenticator code is incorrect");
        }
        admin.setMfaEnabled(true);
        audit.record(principal, "ADMIN_MFA_ENABLED", "AdminUser", admin.getId(), null);
    }

    @Transactional
    public void disableMfa(AdminPrincipal principal, String code) {
        AdminUser admin = get(principal);
        if (!admin.isMfaEnabled() || !Totp.verify(crypto.decrypt(admin.getMfaSecretEnc()), code, Instant.now())) {
            throw ApiException.rule("MFA_INVALID", "The authenticator code is incorrect");
        }
        admin.setMfaEnabled(false);
        admin.setMfaSecretEnc(null);
        audit.record(principal, "ADMIN_MFA_DISABLED", "AdminUser", admin.getId(), null);
    }

    // ------------------------------------------------------------------ staff management (MASTER_ADMIN only)

    @Transactional(readOnly = true)
    public List<AdminUser> listStaff(Storefront sf) {
        return admins.findByStorefrontOrderByCreatedAtAsc(sf);
    }

    @Transactional
    public CreatedStaff createStaff(AdminPrincipal actor, String email, String fullName, AdminRole role) {
        Storefront sf = actor.storefront();
        String normalized = CustomerAuthService.normalizeEmail(email);
        if (admins.findByStorefrontAndEmailIgnoreCase(sf, normalized).isPresent()) {
            throw ApiException.conflict("EMAIL_IN_USE", "An admin with this email already exists in this storefront");
        }
        String temp = temporaryPassword();
        AdminUser user = admins.save(new AdminUser(sf, normalized, passwordEncoder.encode(temp), fullName.trim(), role, true));
        audit.record(actor, "STAFF_CREATED", "AdminUser", user.getId(), "role=" + role + " email=" + normalized);
        return new CreatedStaff(user, temp);
    }

    @Transactional
    public AdminUser updateStaff(AdminPrincipal actor, Long id, AdminRole role, AdminUser.Status status, String fullName) {
        AdminUser user = admins.findByIdAndStorefront(id, actor.storefront())
                .orElseThrow(() -> ApiException.notFound("ADMIN_NOT_FOUND", "Admin not found"));
        boolean losesMaster = user.getRole() == AdminRole.MASTER_ADMIN && user.getStatus() == AdminUser.Status.ACTIVE
                && ((role != null && role != AdminRole.MASTER_ADMIN) || status == AdminUser.Status.DISABLED);
        if (losesMaster && admins.countByStorefrontAndRoleAndStatus(actor.storefront(), AdminRole.MASTER_ADMIN,
                AdminUser.Status.ACTIVE) <= 1) {
            throw ApiException.rule("LAST_MASTER_ADMIN", "The last active Master Admin cannot be demoted or disabled");
        }
        if (role != null) user.setRole(role);
        if (status != null) user.setStatus(status);
        if (fullName != null && !fullName.isBlank()) user.setFullName(fullName.trim());
        audit.record(actor, "STAFF_UPDATED", "AdminUser", id, "role=" + user.getRole() + " status=" + user.getStatus());
        return user;
    }

    @Transactional
    public String resetStaffPassword(AdminPrincipal actor, Long id) {
        AdminUser user = admins.findByIdAndStorefront(id, actor.storefront())
                .orElseThrow(() -> ApiException.notFound("ADMIN_NOT_FOUND", "Admin not found"));
        String temp = temporaryPassword();
        user.setPasswordHash(passwordEncoder.encode(temp));
        user.setMustChangePassword(true);
        user.setMfaEnabled(false);
        user.setMfaSecretEnc(null);
        audit.record(actor, "STAFF_PASSWORD_RESET", "AdminUser", id, null);
        return temp;
    }

    private static String temporaryPassword() {
        return Hashing.randomCode(10) + "-" + Hashing.randomCode(4) + "7";
    }

    public static AdminPrincipal toPrincipal(AdminUser a) {
        return new AdminPrincipal(a.getId(), a.getStorefront(), a.getEmail(), a.getFullName(), a.getRole());
    }

    private static ApiException invalid() {
        return ApiException.unauthorized("INVALID_CREDENTIALS",
                "Invalid email or password, or the account is temporarily locked");
    }
}
