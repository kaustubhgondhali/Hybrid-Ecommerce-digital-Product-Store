package com.hybrid.platform.seo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeoAnalyzerTest {

    private static final String META = "Buy fresh organic green tea leaves from Assam gardens. Hand picked in small batches "
            + "for a rich aroma and a smooth, mellow taste every day.";
    private static final String SUMMARY = "Fresh organic green tea from Assam gardens.";
    private static final String DESCRIPTION = "Brew our green tea for three minutes. "
            + "Each batch is hand picked and packed in small lots to keep the aroma fresh. ".repeat(12);

    private static SeoAnalyzer.Input good() {
        return new SeoAnalyzer.Input("Organic Green Tea Leaves 250g from Assam Gardens", META, null, "Green Tea",
                "organic-green-tea-leaves", "https://example.com/shop/p/organic-green-tea-leaves", SUMMARY, DESCRIPTION,
                3, 0, true, true, true, 2, false, false, false, true);
    }

    private static SeoAnalyzer.Status status(SeoAnalyzer.Analysis a, String id) {
        return a.checks().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow().status();
    }

    @Test
    void wellOptimisedProductPassesEveryCheck() {
        var a = SeoAnalyzer.analyze(good());
        assertThat(a.problems()).as(a.checks().toString()).isZero();
        assertThat(a.score()).isEqualTo(100);
        assertThat(a.grade()).isEqualTo("Good");
    }

    @Test
    void thinHiddenProductWithoutImagesIsPoor() {
        var a = SeoAnalyzer.analyze(new SeoAnalyzer.Input("Tea", null, "Tea", null, "tea", "https://example.com/shop/p/tea",
                null, null, 0, 0, false, false, false, 0, true, true, true, true));
        assertThat(a.score()).isLessThan(50);
        assertThat(a.grade()).isEqualTo("Poor");
        assertThat(status(a, "indexable")).isEqualTo(SeoAnalyzer.Status.FAIL);
        assertThat(status(a, "images")).isEqualTo(SeoAnalyzer.Status.FAIL);
        assertThat(status(a, "content-length")).isEqualTo(SeoAnalyzer.Status.FAIL);
        assertThat(a.checks()).noneMatch(c -> c.id().equals("image-alt"));
    }

    @Test
    void keywordChecksMatchWholeWordsCaseInsensitively() {
        var in = good();
        var a = SeoAnalyzer.analyze(new SeoAnalyzer.Input("Organic Greentea Leaves 250g from Assam Gardens", in.metaDescription(),
                null, "GREEN TEA", in.slug(), in.url(), in.summary(), in.description(), 3, 1, true, true, true, 2, false,
                false, false, true));
        assertThat(status(a, "kw-title")).isEqualTo(SeoAnalyzer.Status.FAIL);
        assertThat(status(a, "kw-description")).isEqualTo(SeoAnalyzer.Status.PASS);
        assertThat(status(a, "image-alt")).isEqualTo(SeoAnalyzer.Status.WARN);
    }

    @Test
    void keywordStuffingFails() {
        var in = good();
        String stuffed = "green tea ".repeat(30) + "is the best drink for the morning and the evening. ".repeat(4);
        var a = SeoAnalyzer.analyze(new SeoAnalyzer.Input(in.effectiveTitle(), in.metaDescription(), null, "green tea",
                in.slug(), in.url(), in.summary(), stuffed, 3, 0, true, true, true, 2, false, false, false, true));
        assertThat(status(a, "kw-density")).isEqualTo(SeoAnalyzer.Status.FAIL);
    }

    @Test
    void serpPreviewTruncatesAtWordBoundaries() {
        String title = "An extremely long product title that goes on and on well past sixty characters";
        var serp = SeoAnalyzer.serp(title, "https://example.com", "short");
        assertThat(serp.titleTruncated()).isTrue();
        assertThat(serp.descriptionTruncated()).isFalse();
        assertThat(serp.title()).endsWith(" \u2026").hasSizeLessThanOrEqualTo(62);
        assertThat(title).startsWith(serp.title().substring(0, serp.title().length() - 2));
    }

    @Test
    void wordCountingIgnoresPunctuationAndPhrasesNeedWholeWords() {
        assertThat(SeoAnalyzer.countWords("Hello, world! 250g - tea.")).isEqualTo(4);
        assertThat(SeoAnalyzer.occurrences("Green tea, GREEN-TEA and greentea", "green tea")).isEqualTo(2);
    }
}
