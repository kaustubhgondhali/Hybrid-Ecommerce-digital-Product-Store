package com.hybrid.platform.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record AppProperties(
        String baseUrl,
        String currency,
        String encryptionKey,
        String hashSalt,
        Storage storage,
        Checkout checkout,
        Delivery delivery,
        Security security,
        Payment payment,
        Mail mail,
        Bootstrap bootstrap,
        /** When true (default), the storefronts only list affiliate offers — no cart or checkout. */
        boolean affiliateOnly) {

    public record Storage(String root) {
    }

    public record Checkout(int reservationMinutes, int maxQuantityPerLine) {
    }

    public record Delivery(int downloadLinkSeconds) {
    }

    public record Security(boolean requireEmailVerification, int maxFailedLogins, int lockMinutes) {
    }

    public record Payment(String provider) {
    }

    public record Mail(boolean logUnsentEmails) {
    }

    public record Bootstrap(String shopAdminEmail, String shopAdminPassword,
                            String digitalAdminEmail, String digitalAdminPassword) {
    }
}
