package com.hybrid.platform.studio;

import com.hybrid.platform.catalog.CatalogPublicService;
import com.hybrid.platform.studio.ContentWriter.BlogKind;
import com.hybrid.platform.studio.ContentWriter.EmailKind;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentWriterTest {

    private static final ContentWriter.Store STORE = new ContentWriter.Store("Elaashop", "https://elaashop.in/shop", false, LocalDate.of(2026, 10, 14));

    private static ProductFacts earbuds(String slug, String title, Long price, Long mrp, Integer discount, Integer rating) {
        return new ProductFacts(1L, slug, title, "Boat", "Earbuds", "earbuds",
                "Wireless earbuds with 42 hours of playback.", List.of("42H playback", "ENx noise cancellation", "IPX4 water resistance"),
                List.of("Long battery life", "Comfortable fit"), List.of("No app support"), "Great value for daily use.", rating,
                List.of(new CatalogPublicService.Spec("Battery", "42 hours"), new CatalogPublicService.Spec("Bluetooth", "5.3")),
                price, mrp, discount, "Amazon", true, false, null, false, null,
                "https://elaashop.in/shop/p/" + slug, "https://elaashop.in/media/x.jpg", "x.jpg");
    }

    private static ProductFacts bare() {
        return new ProductFacts(2L, "plain-mug", "Plain Mug", null, null, null, null, List.of(), List.of(), List.of(), null, null,
                List.of(), null, null, null, null, false, false, null, false, null, "https://elaashop.in/shop/p/plain-mug", null, null);
    }

    @Test
    void everySingleProductBlogEmbedsTheProductAndNeverPrintsNull() {
        ProductFacts p = earbuds("airdopes-141", "Airdopes 141", 109900L, 449000L, 76, 8);
        for (BlogKind k : List.of(BlogKind.REVIEW, BlogKind.WORTH_IT, BlogKind.FAQ, BlogKind.BUYING_TIPS)) {
            ContentWriter.BlogDraft d = ContentWriter.blog(k, p, List.of(), STORE, 0);
            assertThat(d.body()).as(k.name()).contains("[product: airdopes-141]").doesNotContain("null");
            assertThat(d.body().length()).as(k.name()).isGreaterThan(150);
            assertThat(d.slug()).matches("[a-z0-9]+(?:-[a-z0-9]+)*");
            assertThat(d.metaDescription().length()).isLessThanOrEqualTo(160);
            assertThat(d.title()).doesNotContain("null");
        }
        ContentWriter.BlogDraft review = ContentWriter.blog(BlogKind.REVIEW, p, List.of(), STORE, 0);
        assertThat(review.body()).contains("₹1,099").contains("Boat Airdopes 141").contains("No app support");
        assertThat(review.body()).containsIgnoringCase("commission");
    }

    @Test
    void productsWithoutDataStillGetSensibleTextAndNoPrices() {
        ProductFacts p = bare();
        for (BlogKind k : List.of(BlogKind.REVIEW, BlogKind.WORTH_IT, BlogKind.FAQ, BlogKind.BUYING_TIPS)) {
            ContentWriter.BlogDraft d = ContentWriter.blog(k, p, List.of(), STORE, 1);
            assertThat(d.body()).as(k.name()).doesNotContain("null").doesNotContain("₹").doesNotContain("commission");
        }
        ContentWriter.StoryDraft story = ContentWriter.story(p, STORE, 0);
        assertThat(story.pages()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(story.pages()).allSatisfy(pg -> assertThat(pg.headline()).isNotBlank());
        assertThat(ContentWriter.social(p, STORE, 0)).allSatisfy(s -> assertThat(s.text()).doesNotContain("null").doesNotContain("₹"));
    }

    @Test
    void anotherVersionChangesTheWording() {
        ProductFacts p = earbuds("airdopes-141", "Airdopes 141", 109900L, null, null, null);
        String a = ContentWriter.blog(BlogKind.REVIEW, p, List.of(), STORE, 0).body();
        String b = ContentWriter.blog(BlogKind.REVIEW, p, List.of(), STORE, 1).body();
        assertThat(a).isNotEqualTo(b);
        assertThat(ContentWriter.social(p, STORE, 0).get(0).text()).isNotEqualTo(ContentWriter.social(p, STORE, 1).get(0).text());
    }

    @Test
    void socialPostsFitEachNetworkAndLinkToTheProductPage() {
        ProductFacts p = earbuds("airdopes-141", "Airdopes 141 Bluetooth Truly Wireless Earbuds with Very Long Name", 109900L, 449000L, 76, 8);
        List<ContentWriter.SocialPost> posts = ContentWriter.social(p, STORE, 0);
        assertThat(posts).extracting(ContentWriter.SocialPost::channel)
                .contains("instagram", "facebook", "pinterest", "linkedin", "x", "whatsapp", "telegram");
        for (ContentWriter.SocialPost s : posts) {
            if (!s.channel().equals("x")) assertThat(s.text().length()).as(s.channel()).isLessThanOrEqualTo(s.maxLength());
            if (s.channel().equals("pinterest")) {
                assertThat(s.shareUrl()).contains("url=" + java.net.URLEncoder.encode(p.productUrl(), java.nio.charset.StandardCharsets.UTF_8))
                        .contains("media=");
            } else if (!s.channel().equals("instagram")) {
                assertThat(s.text()).as(s.channel()).contains(p.productUrl());
                assertThat(s.shareUrl()).as(s.channel()).startsWith("https://");
            }
        }
        ContentWriter.SocialPost x = posts.stream().filter(s -> s.channel().equals("x")).findFirst().orElseThrow();
        assertThat(x.text().replace(p.productUrl(), "x".repeat(23)).length()).isLessThanOrEqualTo(280);
        ContentWriter.SocialPost wa = posts.stream().filter(s -> s.channel().equals("whatsapp")).findFirst().orElseThrow();
        assertThat(wa.shareUrl()).startsWith("https://wa.me/?text=");
        ContentWriter.SocialPost ig = posts.stream().filter(s -> s.channel().equals("instagram")).findFirst().orElseThrow();
        assertThat(ig.text()).contains("#");
    }

    @Test
    void comparisonsRoundupsAndNewsletters() {
        ProductFacts a = earbuds("airdopes-141", "Airdopes 141", 109900L, 449000L, 76, 8);
        ProductFacts b = earbuds("noise-vs104", "Noise VS104", 89900L, 299900L, 70, 7);
        ProductFacts c = earbuds("jbl-wave-200", "JBL Wave 200", 299900L, null, null, 9);

        ContentWriter.BlogDraft cmp = ContentWriter.comparison(List.of(a, b), STORE, 0);
        assertThat(cmp.body()).contains("| Price |").contains("[products: airdopes-141, noise-vs104]").contains("Best on a budget: Boat Noise VS104");
        assertThatThrownBy(() -> ContentWriter.comparison(List.of(a), STORE, 0)).isInstanceOf(IllegalArgumentException.class);

        ContentWriter.BlogDraft round = ContentWriter.roundup("wireless earbuds", List.of(a, b, c), STORE, 0);
        assertThat(round.title()).isEqualTo("Best wireless earbuds in October 2026");
        assertThat(round.body()).contains("Best overall: Boat JBL Wave 200").contains("Biggest discount: Boat Airdopes 141 (76% off)");

        ContentWriter.EmailDraft launch = ContentWriter.email(EmailKind.LAUNCH, List.of(a), null, STORE, 0);
        assertThat(launch.body()).startsWith("Hi {name},").contains(a.productUrl()).contains("₹1,099");
        assertThat(launch.subject()).doesNotContain("\n").hasSizeLessThanOrEqualTo(200);
        ContentWriter.EmailDraft deals = ContentWriter.email(EmailKind.TOP_DEALS, List.of(c, b, a), null, STORE, 0);
        assertThat(deals.body().indexOf("Airdopes")).isLessThan(deals.body().indexOf("JBL"));
        assertThat(deals.body()).contains("{link}");
    }
}
