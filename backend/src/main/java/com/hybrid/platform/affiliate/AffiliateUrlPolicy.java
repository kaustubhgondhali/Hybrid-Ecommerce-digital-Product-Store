package com.hybrid.platform.affiliate;

import com.hybrid.platform.common.error.ApiException;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Accepts any affiliate URL from any program (tags, deep links, short links are preserved exactly),
 * while rejecting URLs that could be abused: non-http(s) schemes, embedded credentials, IP literals,
 * localhost / internal hostnames.
 */
public final class AffiliateUrlPolicy {

    public static final int MAX_LENGTH = 2048;
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern PARAM_NAME = Pattern.compile("^[A-Za-z0-9_.\\-\\[\\]]{1,40}$");

    private AffiliateUrlPolicy() {
    }

    public static URI parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid("Enter the affiliate link");
        }
        String value = raw.trim();
        if (value.length() > MAX_LENGTH) {
            throw invalid("The link is longer than " + MAX_LENGTH + " characters");
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw invalid("This is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw invalid("The link must start with https://");
        }
        if (uri.getRawUserInfo() != null) {
            throw invalid("Links containing a username or password are not allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw invalid("The link has no website address");
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") || h.contains(":") || IPV4.matcher(h).matches()) {
            throw invalid("Links to IP addresses are not allowed");
        }
        if (!h.contains(".") || h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local")
                || h.endsWith(".internal") || h.endsWith(".lan") || h.endsWith(".home.arpa")) {
            throw invalid("Links to local or internal addresses are not allowed");
        }
        return uri;
    }

    public static void requireAllowedScheme(URI uri, boolean allowHttp) {
        if ("http".equalsIgnoreCase(uri.getScheme()) && !allowHttp) {
            throw ApiException.rule("AFFILIATE_HTTP_NOT_ALLOWED",
                    "This merchant only allows https:// links (enable http for the merchant if the program requires it)");
        }
    }

    /** Merchant domain key: lowercase host without a leading "www.". */
    public static String domainKey(URI uri) {
        String h = uri.getHost().toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    public static String hostKey(URI uri) {
        String h = uri.getHost().toLowerCase(Locale.ROOT);
        return h.endsWith(".") ? h.substring(0, h.length() - 1) : h;
    }

    public static void requireValidParamName(String name) {
        if (name != null && !name.isBlank() && !PARAM_NAME.matcher(name.trim()).matches()) {
            throw ApiException.rule("AFFILIATE_SUBID_INVALID", "Sub-ID parameter name may contain only letters, numbers, _ - . [ ]");
        }
    }

    /** Appends the click reference as a sub-ID parameter, keeping every existing parameter and fragment intact. */
    public static String withSubId(String url, String param, String value) {
        if (param == null || param.isBlank()) {
            return url;
        }
        String fragment = "";
        int hash = url.indexOf('#');
        String base = url;
        if (hash >= 0) {
            fragment = url.substring(hash);
            base = url.substring(0, hash);
        }
        String sep = base.contains("?") ? (base.endsWith("?") || base.endsWith("&") ? "" : "&") : "?";
        return base + sep + URLEncoder.encode(param, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(value, StandardCharsets.UTF_8) + fragment;
    }

    private static ApiException invalid(String message) {
        return ApiException.rule("AFFILIATE_URL_INVALID", message);
    }
}
