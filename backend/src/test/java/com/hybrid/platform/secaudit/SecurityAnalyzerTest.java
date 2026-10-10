package com.hybrid.platform.secaudit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityAnalyzerTest {

    private static SecurityAnalyzer.Snapshot snap(boolean https, boolean cookieSecure, boolean enc, boolean salt,
                                                  boolean prod, boolean rate, boolean emailVerify, boolean demoPay,
                                                  boolean demoCat, boolean jdbc, int staff, int mfa, int masters,
                                                  int mastersMfa, int allowHttp, long errors, boolean hsts) {
        return new SecurityAnalyzer.Snapshot(https, cookieSecure, enc, salt, prod, rate, emailVerify, demoPay, demoCat,
                jdbc, 5, 15, staff, mfa, masters, mastersMfa, 0, allowHttp, errors, true, true, true, hsts, true, true);
    }

    private static SecurityAnalyzer.Status status(SecurityAnalyzer.Analysis a, String id) {
        return a.checks().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow().status();
    }

    @Test
    void hardenedProductionLooksStrong() {
        var a = SecurityAnalyzer.analyze(snap(true, true, true, true, true, true, true, false, false, true,
                2, 2, 1, 1, 0, 0, true));
        assertThat(a.critical()).isZero();
        assertThat(a.problems()).isZero();
        assertThat(a.score()).isEqualTo(100);
        assertThat(a.grade()).isEqualTo("Strong");
    }

    @Test
    void insecureLocalDefaultsAreFlaggedButNotAllCritical() {
        var a = SecurityAnalyzer.analyze(snap(false, false, false, false, false, true, false, true, true, true,
                1, 0, 1, 0, 0, 0, false));
        assertThat(status(a, "https")).isEqualTo(SecurityAnalyzer.Status.FAIL);
        assertThat(status(a, "encryption-key")).isEqualTo(SecurityAnalyzer.Status.FAIL);
        assertThat(status(a, "mfa-master")).isEqualTo(SecurityAnalyzer.Status.FAIL);
        assertThat(status(a, "cookie-secure")).isEqualTo(SecurityAnalyzer.Status.WARN);
        assertThat(status(a, "payments")).isEqualTo(SecurityAnalyzer.Status.WARN);
        assertThat(a.critical()).isGreaterThanOrEqualTo(3);
        assertThat(a.score()).isLessThan(70);
        assertThat(a.grade()).isIn("At risk", "Needs work");
    }

    @Test
    void demoPaymentsUnderProdAreCritical() {
        var a = SecurityAnalyzer.analyze(snap(true, true, true, true, true, true, true, true, false, true,
                1, 1, 1, 1, 0, 0, true));
        assertThat(status(a, "payments")).isEqualTo(SecurityAnalyzer.Status.FAIL);
    }

    @Test
    void affiliateHttpAndErrorsAreWarnings() {
        var a = SecurityAnalyzer.analyze(snap(true, true, true, true, false, true, true, false, false, true,
                1, 1, 1, 1, 2, 3, true));
        assertThat(status(a, "affiliate-http")).isEqualTo(SecurityAnalyzer.Status.WARN);
        assertThat(status(a, "error-log")).isEqualTo(SecurityAnalyzer.Status.WARN);
        assertThat(a.checks().stream().filter(c -> c.status() == SecurityAnalyzer.Status.PASS))
                .allMatch(c -> c.fix() == null);
    }
}
