package com.hybrid.platform.identity;

import com.hybrid.platform.common.AppProperties;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.crypto.Hashing;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.security.CustomerPrincipal;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

@Service
public class CustomerAuthService {

    private static final Duration VERIFY_TTL = Duration.ofHours(48);
    private static final Duration RESET_TTL = Duration.ofMinutes(30);

    private final CustomerRepository customers;
    private final AuthTokenRepository tokens;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final AppProperties props;
    private final String dummyHash;

    public CustomerAuthService(CustomerRepository customers, AuthTokenRepository tokens,
                               PasswordEncoder passwordEncoder, ApplicationEventPublisher events, AppProperties props) {
        this.customers = customers;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
        this.props = props;
        this.dummyHash = passwordEncoder.encode(Hashing.randomToken());
    }

    @Transactional
    public CustomerPrincipal register(String email, String password, String fullName, Storefront storefront) {
        return register(email, password, fullName, storefront, false);
    }

    @Transactional
    public CustomerPrincipal register(String email, String password, String fullName, Storefront storefront,
                                      boolean marketingOptIn) {
        String normalized = normalizeEmail(email);
        PasswordPolicy.check(password);
        if (customers.existsByEmailIgnoreCase(normalized)) {
            throw ApiException.conflict("EMAIL_IN_USE", "An account with this email already exists");
        }
        Customer created = new Customer(normalized, passwordEncoder.encode(password), fullName.trim());
        created.setSignupStorefront(storefront);
        Customer customer = customers.save(created);
        String token = issueToken(customer.getId(), AuthToken.Purpose.EMAIL_VERIFY, VERIFY_TTL);
        events.publishEvent(new IdentityEvents.CustomerRegistered(customer.getId(), customer.getEmail(),
                customer.getFullName(), token, storefront));
        if (marketingOptIn && storefront != null) {
            events.publishEvent(new IdentityEvents.MarketingOptedIn(customer.getId(), storefront));
        }
        return toPrincipal(customer);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public CustomerPrincipal login(String email, String password) {
        Optional<Customer> found = customers.findByEmailIgnoreCase(normalizeEmail(email));
        Instant now = Instant.now();
        if (found.isEmpty()) {
            passwordEncoder.matches(password, dummyHash);
            throw invalidCredentials();
        }
        Customer customer = found.get();
        if (customer.isLocked(now) || customer.getStatus() != Customer.Status.ACTIVE) {
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(password, customer.getPasswordHash())) {
            customer.recordFailure(props.security().maxFailedLogins(), props.security().lockMinutes());
            throw invalidCredentials();
        }
        customer.recordSuccess();
        return toPrincipal(customer);
    }

    @Transactional
    public void verifyEmail(String token) {
        AuthToken t = tokens.findByTokenHash(Hashing.sha256Hex(token))
                .filter(x -> x.isUsable(AuthToken.Purpose.EMAIL_VERIFY, Instant.now()))
                .orElseThrow(() -> ApiException.rule("TOKEN_INVALID", "This verification link is invalid or expired"));
        t.markUsed();
        customers.findById(t.getCustomerId()).ifPresent(c -> c.setEmailVerified(true));
    }

    @Transactional
    public void resendVerification(Long customerId, Storefront storefront) {
        Customer c = get(customerId);
        if (c.isEmailVerified()) {
            return;
        }
        String token = issueToken(c.getId(), AuthToken.Purpose.EMAIL_VERIFY, VERIFY_TTL);
        events.publishEvent(new IdentityEvents.CustomerRegistered(c.getId(), c.getEmail(), c.getFullName(), token, storefront));
    }

    /** Always succeeds from the caller's perspective so it cannot be used to discover registered emails. */
    @Transactional
    public void requestPasswordReset(String email, Storefront storefront) {
        customers.findByEmailIgnoreCase(normalizeEmail(email))
                .filter(c -> c.getStatus() == Customer.Status.ACTIVE)
                .ifPresent(c -> {
                    String token = issueToken(c.getId(), AuthToken.Purpose.PASSWORD_RESET, RESET_TTL);
                    events.publishEvent(new IdentityEvents.PasswordResetRequested(c.getEmail(), c.getFullName(), token, storefront));
                });
    }

    @Transactional
    public void confirmPasswordReset(String token, String newPassword) {
        PasswordPolicy.check(newPassword);
        AuthToken t = tokens.findByTokenHash(Hashing.sha256Hex(token))
                .filter(x -> x.isUsable(AuthToken.Purpose.PASSWORD_RESET, Instant.now()))
                .orElseThrow(() -> ApiException.rule("TOKEN_INVALID", "This reset link is invalid or expired"));
        t.markUsed();
        Customer c = get(t.getCustomerId());
        c.setPasswordHash(passwordEncoder.encode(newPassword));
        c.setEmailVerified(true);
    }

    @Transactional
    public Customer updateProfile(Long customerId, String fullName, String phone) {
        Customer c = get(customerId);
        c.setFullName(fullName.trim());
        c.setPhone(phone == null || phone.isBlank() ? null : phone.trim());
        return c;
    }

    @Transactional
    public void changePassword(Long customerId, String currentPassword, String newPassword) {
        Customer c = get(customerId);
        if (!passwordEncoder.matches(currentPassword, c.getPasswordHash())) {
            throw ApiException.rule("CURRENT_PASSWORD_WRONG", "Current password is incorrect");
        }
        PasswordPolicy.check(newPassword);
        c.setPasswordHash(passwordEncoder.encode(newPassword));
    }

    @Transactional(readOnly = true)
    public Customer get(Long id) {
        return customers.findById(id).orElseThrow(() -> ApiException.unauthorized("UNAUTHENTICATED", "Please sign in"));
    }

    private String issueToken(Long customerId, AuthToken.Purpose purpose, Duration ttl) {
        String raw = Hashing.randomToken();
        tokens.save(new AuthToken(Hashing.sha256Hex(raw), purpose, customerId, Instant.now().plus(ttl)));
        return raw;
    }

    private static CustomerPrincipal toPrincipal(Customer c) {
        return new CustomerPrincipal(c.getId(), c.getEmail(), c.getFullName());
    }

    static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static ApiException invalidCredentials() {
        return ApiException.unauthorized("INVALID_CREDENTIALS",
                "Invalid email or password, or the account is temporarily locked");
    }
}
