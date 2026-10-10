package com.hybrid.platform.identity;

import com.hybrid.platform.common.AppProperties;
import com.hybrid.platform.common.Storefront;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first Master Admin of a storefront from environment variables, only if that storefront has
 * no active Master Admin yet. The admin must change the password on first sign-in.
 * There is no HTTP endpoint that can create a Master Admin out of nothing.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AdminUserRepository admins;
    private final PasswordEncoder encoder;
    private final AppProperties props;

    public AdminBootstrap(AdminUserRepository admins, PasswordEncoder encoder, AppProperties props) {
        this.admins = admins;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AppProperties.Bootstrap b = props.bootstrap();
        if (b == null) {
            return;
        }
        create(Storefront.SHOP, b.shopAdminEmail(), b.shopAdminPassword());
        create(Storefront.DIGITAL, b.digitalAdminEmail(), b.digitalAdminPassword());
    }

    private void create(Storefront sf, String email, String password) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            if (admins.countByStorefrontAndRoleAndStatus(sf, AdminRole.MASTER_ADMIN, AdminUser.Status.ACTIVE) == 0) {
                log.warn("No Master Admin exists for {}. Set BOOTSTRAP_{}_ADMIN_EMAIL and BOOTSTRAP_{}_ADMIN_PASSWORD to create one.",
                        sf, sf, sf);
            }
            return;
        }
        if (admins.countByStorefrontAndRoleAndStatus(sf, AdminRole.MASTER_ADMIN, AdminUser.Status.ACTIVE) > 0) {
            return;
        }
        PasswordPolicy.check(password);
        String normalized = CustomerAuthService.normalizeEmail(email);
        admins.save(new AdminUser(sf, normalized, encoder.encode(password), sf == Storefront.SHOP
                ? "Elaashop Master Admin" : "Elaatech Master Admin", AdminRole.MASTER_ADMIN, true));
        log.info("Created first {} Master Admin {} (password change required at first sign-in)", sf, normalized);
    }
}
