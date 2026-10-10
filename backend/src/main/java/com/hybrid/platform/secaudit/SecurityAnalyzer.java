package com.hybrid.platform.secaudit;

import java.util.ArrayList;
import java.util.List;

/**
 * Defensive security posture score for one storefront. Checks configuration and account hygiene only —
 * it does not probe the network, guess passwords, or produce attack steps.
 */
public final class SecurityAnalyzer {

    public enum Status { PASS, WARN, FAIL }

    public record Check(String id, String category, String label, Status status, String message, String fix, int weight) {
    }

    public record Analysis(int score, String grade, long problems, long critical, List<Check> checks) {
    }

    /** Facts gathered from config and the storefront's own data. */
    public record Snapshot(boolean httpsBaseUrl, boolean cookieSecure, boolean encryptionKeySet, boolean hashSaltSet,
                           boolean prodProfile, boolean rateLimitEnabled, boolean emailVerificationRequired,
                           boolean demoPayments, boolean demoCatalog, boolean jdbcSessions,
                           int maxFailedLogins, int lockMinutes, int activeStaff, int staffWithMfa,
                           int masterAdmins, int masterAdminsWithMfa, int staffMustChangePassword,
                           int affiliateMerchantsAllowHttp, long unresolvedErrors, boolean cspEnabled,
                           boolean frameDeny, boolean csrfEnabled, boolean hstsEnabled, boolean permissionsPolicyEnabled,
                           boolean sessionRotatedOnLogin) {
    }

    private SecurityAnalyzer() {
    }

    public static Analysis analyze(Snapshot s) {
        List<Check> checks = new ArrayList<>();

        // --- transport & cookies
        checks.add(s.httpsBaseUrl()
                ? pass("https", "Transport", "HTTPS site address",
                "APP_BASE_URL uses https://, so links, emails and the sitemap point to a secure origin.", 10)
                : fail("https", "Transport", "HTTPS site address",
                "APP_BASE_URL is not https://. Browsers and search engines will treat the site as insecure.",
                "Set APP_BASE_URL=https://your-domain and serve the app behind HTTPS.", 10));

        checks.add(s.cookieSecure()
                ? pass("cookie-secure", "Transport", "Secure session cookies",
                "Session and CSRF cookies are marked Secure (HTTPS only).", 10)
                : (s.httpsBaseUrl() || s.prodProfile()
                ? fail("cookie-secure", "Transport", "Secure session cookies",
                "Cookies can be sent over plain HTTP, so a network attacker could steal a signed-in session.",
                "Set COOKIE_SECURE=true (required for production).", 10)
                : warn("cookie-secure", "Transport", "Secure session cookies",
                "COOKIE_SECURE is off. Fine for local http://localhost, but turn it on before going live.",
                "Set COOKIE_SECURE=true when you serve over HTTPS.", 10)));

        checks.add(s.hstsEnabled()
                ? pass("hsts", "Transport", "HSTS",
                "Strict-Transport-Security is enabled so browsers refuse plain HTTP after the first visit.", 6)
                : (s.cookieSecure()
                ? warn("hsts", "Transport", "HSTS",
                "Secure cookies are on, but HSTS is not yet advertised.",
                "Restart after enabling COOKIE_SECURE — the app turns HSTS on with secure cookies.", 6)
                : warn("hsts", "Transport", "HSTS",
                "HSTS stays off until secure cookies are enabled (so local HTTP development is not broken).",
                "Enable COOKIE_SECURE=true in production; HSTS follows automatically.", 6)));

        // --- secrets
        checks.add(s.encryptionKeySet()
                ? pass("encryption-key", "Secrets", "Encryption key",
                "APP_ENCRYPTION_KEY is set. SMTP passwords, MFA secrets and licence keys stay decryptable across restarts.", 10)
                : fail("encryption-key", "Secrets", "Encryption key",
                "APP_ENCRYPTION_KEY is missing. Encrypted values are lost on every restart.",
                "Generate 32 random bytes as base64 and set APP_ENCRYPTION_KEY in .env (see .env.example).", 10));

        checks.add(s.hashSaltSet()
                ? pass("hash-salt", "Secrets", "Hash salt",
                "APP_HASH_SALT is set for hashed affiliate click IPs.", 4)
                : warn("hash-salt", "Secrets", "Hash salt",
                "APP_HASH_SALT is missing; a temporary salt is used until restart.",
                "Set a long random APP_HASH_SALT in .env.", 4));

        // --- payments & demo data
        checks.add(!s.demoPayments()
                ? pass("payments", "Commerce", "Live payments",
                "A real payment provider is configured (not the demo simulator).", 10)
                : (s.prodProfile()
                ? fail("payments", "Commerce", "Live payments",
                "Demo payments are active under the prod profile — real money must never use the simulator.",
                "Configure Razorpay (or another provider) and set PAYMENT_PROVIDER accordingly.", 10)
                : warn("payments", "Commerce", "Live payments",
                "Demo payments are active. Checkout is simulated and no money is collected.",
                "Keep demo for testing; switch PAYMENT_PROVIDER when you go live. The demo provider refuses to start under prod.", 10)));

        checks.add(!s.demoCatalog()
                ? pass("demo-catalog", "Commerce", "Demo catalog",
                "Automatic demo product seeding is off.", 3)
                : warn("demo-catalog", "Commerce", "Demo catalog",
                "DEMO_CATALOG is on — missing [Demo] products are recreated on startup.",
                "Delete demo products in the catalog, then set DEMO_CATALOG=false in .env before launch.", 3));

        // --- auth & staff
        checks.add(s.rateLimitEnabled()
                ? pass("rate-limit", "Access", "Rate limiting",
                "Sign-in, registration, checkout, reviews and affiliate redirects are rate limited.", 8)
                : fail("rate-limit", "Access", "Rate limiting",
                "Rate limiting is disabled. Brute-force and abuse attempts are not slowed down.",
                "Set RATE_LIMIT_ENABLED=true (the default).", 8));

        if (s.maxFailedLogins() >= 3 && s.maxFailedLogins() <= 20 && s.lockMinutes() >= 5 && s.lockMinutes() <= 1440) {
            checks.add(pass("lockout", "Access", "Login lockout",
                    "Accounts lock after " + s.maxFailedLogins() + " failed attempts for " + s.lockMinutes() + " minutes.", 5));
        } else {
            checks.add(warn("lockout", "Access", "Login lockout",
                    "Lockout settings look unusual (max " + s.maxFailedLogins() + " failures / " + s.lockMinutes() + " min).",
                    "Use roughly 3–10 failures and 5–60 minutes of lock.", 5));
        }

        checks.add(s.emailVerificationRequired()
                ? pass("email-verify", "Access", "Email verification before checkout",
                "Customers must verify their email before placing an order.", 6)
                : warn("email-verify", "Access", "Email verification before checkout",
                "Unverified emails can place orders (abuse and fake accounts are easier).",
                "Set REQUIRE_EMAIL_VERIFICATION=true after SMTP works in Settings → Email.", 6));

        if (s.activeStaff() == 0) {
            checks.add(fail("mfa-staff", "Staff", "Staff MFA",
                    "No active staff accounts were found for this storefront.",
                    "Create a master admin via BOOTSTRAP_* env vars or Staff.", 10));
        } else if (s.staffWithMfa() == s.activeStaff()) {
            checks.add(pass("mfa-staff", "Staff", "Staff MFA",
                    "All " + s.activeStaff() + " active staff have an authenticator app enabled.", 10));
        } else if (s.staffWithMfa() > 0) {
            checks.add(warn("mfa-staff", "Staff", "Staff MFA",
                    s.staffWithMfa() + " of " + s.activeStaff() + " staff have MFA. Stolen passwords can still open the admin.",
                    "Ask every staff member to turn on MFA under My account.", 10));
        } else {
            checks.add(fail("mfa-staff", "Staff", "Staff MFA",
                    "No staff account has MFA. A leaked password is enough to take over the admin.",
                    "Sign in → My account → enable authenticator MFA for every admin, starting with Master Admin.", 10));
        }

        if (s.masterAdmins() == 0) {
            checks.add(fail("mfa-master", "Staff", "Master Admin MFA",
                    "There is no active Master Admin.",
                    "Bootstrap one Master Admin from the environment variables.", 8));
        } else if (s.masterAdminsWithMfa() == s.masterAdmins()) {
            checks.add(pass("mfa-master", "Staff", "Master Admin MFA",
                    "Every Master Admin has MFA enabled.", 8));
        } else {
            checks.add(fail("mfa-master", "Staff", "Master Admin MFA",
                    (s.masterAdmins() - s.masterAdminsWithMfa()) + " Master Admin account(s) still without MFA.",
                    "Enable MFA on every Master Admin before inviting more staff.", 8));
        }

        checks.add(s.staffMustChangePassword() == 0
                ? pass("password-change", "Staff", "Forced password change",
                "No staff are stuck mid password-change.", 3)
                : warn("password-change", "Staff", "Forced password change",
                s.staffMustChangePassword() + " staff still must change their password (bootstrap or reset).",
                "Have them sign in and set a new password, or disable unused accounts.", 3));

        // --- sessions & application defences
        checks.add(s.jdbcSessions()
                ? pass("sessions", "Application", "Session store",
                "Sessions live in MySQL, so restarts and multiple instances keep users signed in securely.", 5)
                : warn("sessions", "Application", "Session store",
                "Sessions are only in server memory and disappear on restart.",
                "Use the default Spring Session JDBC configuration (already enabled in production builds).", 5));

        checks.add(s.csrfEnabled()
                ? pass("csrf", "Application", "CSRF protection",
                "State-changing API calls need the XSRF-TOKEN cookie / header pair.", 6)
                : fail("csrf", "Application", "CSRF protection",
                "CSRF protection appears disabled.",
                "Do not disable CSRF in SecurityConfig.", 6));

        checks.add(s.cspEnabled()
                ? pass("csp", "Application", "Content-Security-Policy",
                "CSP blocks unexpected scripts, frames and third-party loads.", 6)
                : fail("csp", "Application", "Content-Security-Policy",
                "No Content-Security-Policy header is configured.",
                "Keep the CSP directives in SecurityConfig.", 6));

        checks.add(s.frameDeny()
                ? pass("clickjacking", "Application", "Clickjacking protection",
                "X-Frame-Options / frame-ancestors deny embedding this site in other pages.", 4)
                : warn("clickjacking", "Application", "Clickjacking protection",
                "Frame embedding is not denied.",
                "Keep frameOptions.deny() and CSP frame-ancestors 'none'.", 4));

        checks.add(s.permissionsPolicyEnabled()
                ? pass("permissions-policy", "Application", "Permissions-Policy",
                "Camera, microphone, geolocation and similar browser features are denied by default.", 3)
                : warn("permissions-policy", "Application", "Permissions-Policy",
                "Permissions-Policy header is not set.",
                "Upgrade to a build that sends Permissions-Policy from SecurityConfig.", 3));

        checks.add(s.sessionRotatedOnLogin()
                ? pass("session-fixation", "Application", "Session fixation defence",
                "A new session id is issued on successful sign-in.", 4)
                : warn("session-fixation", "Application", "Session fixation defence",
                "Session id rotation on login was not confirmed.",
                "Keep SessionAuth.changeSessionId() on login.", 4));

        // --- storefront-specific
        checks.add(s.affiliateMerchantsAllowHttp() == 0
                ? pass("affiliate-http", "Storefront", "Affiliate HTTPS only",
                "No affiliate merchants allow plain http:// destinations.", 5)
                : warn("affiliate-http", "Storefront", "Affiliate HTTPS only",
                s.affiliateMerchantsAllowHttp() + " merchant(s) allow insecure http:// redirects.",
                "Open Affiliate → Merchants and turn off “allow HTTP” unless a partner truly requires it.", 5));

        checks.add(s.unresolvedErrors() == 0
                ? pass("error-log", "Storefront", "Unresolved server errors",
                "No unresolved unexpected errors for this storefront.", 4)
                : warn("error-log", "Storefront", "Unresolved server errors",
                s.unresolvedErrors() + " unresolved error group(s). Attackers often leave traces in 500s.",
                "Review them under System health and mark resolved after you fix the cause.", 4));

        double total = 0, got = 0;
        long problems = 0, critical = 0;
        for (Check c : checks) {
            total += c.weight();
            got += c.weight() * (c.status() == Status.PASS ? 1 : c.status() == Status.WARN ? 0.5 : 0);
            if (c.status() != Status.PASS) problems++;
            if (c.status() == Status.FAIL) critical++;
        }
        int score = total == 0 ? 0 : (int) Math.round(got * 100 / total);
        return new Analysis(score, grade(score), problems, critical, List.copyOf(checks));
    }

    public static String grade(int score) {
        return score >= 85 ? "Strong" : score >= 70 ? "Good" : score >= 50 ? "Needs work" : "At risk";
    }

    private static Check pass(String id, String cat, String label, String message, int w) {
        return new Check(id, cat, label, Status.PASS, message, null, w);
    }

    private static Check warn(String id, String cat, String label, String message, String fix, int w) {
        return new Check(id, cat, label, Status.WARN, message, fix, w);
    }

    private static Check fail(String id, String cat, String label, String message, String fix, int w) {
        return new Check(id, cat, label, Status.FAIL, message, fix, w);
    }
}
