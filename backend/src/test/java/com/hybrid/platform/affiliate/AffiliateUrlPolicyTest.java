package com.hybrid.platform.affiliate;

import com.hybrid.platform.common.error.ApiException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AffiliateUrlPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.amazon.in/dp/B0TEST?tag=store-21",
            "https://amzn.to/3abcDEF",
            "https://www.flipkart.com/item/p/itm123?affid=abc&affExtParam1=x",
            "https://clk.tradedoubler.com/click?p=1&a=2&url=https%3A%2F%2Fshop.example%2Fp",
            "https://shop.example.co.uk/product#reviews",
            "http://legacy-program.example.com/r?id=9"
    })
    void acceptsRealWorldAffiliateLinks(String url) {
        URI uri = AffiliateUrlPolicy.parse(url);
        assertThat(uri.toString()).isEqualTo(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "ftp://files.example.com/x",
            "https://user:pass@example.com/",
            "https://127.0.0.1/admin",
            "https://[::1]/",
            "https://localhost/",
            "https://printer.local/",
            "https://intranet/",
            "not a url"
    })
    void rejectsUnsafeLinks(String url) {
        assertThatThrownBy(() -> AffiliateUrlPolicy.parse(url)).isInstanceOf(ApiException.class);
    }

    @Test
    void httpNeedsMerchantOptIn() {
        URI uri = AffiliateUrlPolicy.parse("http://legacy.example.com/r");
        assertThatThrownBy(() -> AffiliateUrlPolicy.requireAllowedScheme(uri, false)).isInstanceOf(ApiException.class);
        AffiliateUrlPolicy.requireAllowedScheme(uri, true);
    }

    @Test
    void domainKeyStripsWww() {
        assertThat(AffiliateUrlPolicy.domainKey(URI.create("https://WWW.Amazon.in/x"))).isEqualTo("amazon.in");
    }

    @Test
    void subIdIsAppendedWithoutTouchingExistingParamsOrFragment() {
        assertThat(AffiliateUrlPolicy.withSubId("https://m.example/p?tag=a#top", "subid", "c 1"))
                .isEqualTo("https://m.example/p?tag=a&subid=c+1#top");
        assertThat(AffiliateUrlPolicy.withSubId("https://m.example/p", "sid", "x")).isEqualTo("https://m.example/p?sid=x");
        assertThat(AffiliateUrlPolicy.withSubId("https://m.example/p", null, "x")).isEqualTo("https://m.example/p");
    }
}
