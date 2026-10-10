package com.hybrid.platform.content;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContentMarkupTest {

    @Test
    void rendersHeadingsListsNotesAndParagraphs() {
        String html = ContentMarkup.toHtml("# Returns\nWithin 7 days.\nUnused items only.\n\n- Item one\n- Item two\n> Keep the bill\n## Contact");
        assertThat(html).isEqualTo("<h2>Returns</h2>\n<p>Within 7 days.<br>Unused items only.</p>\n"
                + "<ul>\n<li>Item one</li>\n<li>Item two</li>\n</ul>\n<p class=\"note\">Keep the bill</p>\n<h3>Contact</h3>\n");
    }

    @Test
    void escapesHtmlAndRefusesScriptLinks() {
        String html = ContentMarkup.toHtml("<script>alert(1)</script> [click](javascript:alert(1))");
        assertThat(html).doesNotContain("<script>").doesNotContain("<a ").contains("&lt;script&gt;");
    }

    @Test
    void linksInternalAndExternalUrls() {
        String html = ContentMarkup.toHtml("See [our policy](/shop/pages/refunds) or [Google](https://google.com).");
        assertThat(html).contains("<a href=\"/shop/pages/refunds\">our policy</a>")
                .contains("<a href=\"https://google.com\" rel=\"noopener\" target=\"_blank\">Google</a>");
    }

    @Test
    void attributeBreakoutInLinkTextOrUrlIsEscaped() {
        String html = ContentMarkup.toHtml("[x\" onmouseover=\"a](/p\"onclick=\"b)");
        assertThat(html).doesNotContain("\" onmouseover").doesNotContain("\"onclick");
    }

    @Test
    void plainTextDropsMarkup() {
        assertThat(ContentMarkup.toPlainText("# Title\n- one [link](/a)\n> note\n\nbody"))
                .isEqualTo("Title one link note body");
        assertThat(ContentMarkup.toHtml("  ")).isEmpty();
    }

    @Test
    void rendersComparisonTableWithHeaderAndRowLabels() {
        String html = ContentMarkup.toHtml("Intro\n| Feature | Phone A | Phone B |\n|---|:---:|---|\n"
                + "| Battery | 5000 mAh | [Deal](/shop/p/b) |\n| Weight | <b>190 g</b>\nAfter");
        assertThat(html).isEqualTo("<p>Intro</p>\n"
                + "<div class=\"table-wrap\"><table class=\"compare\">\n<thead><tr>"
                + "<th scope=\"col\">Feature</th><th scope=\"col\">Phone A</th><th scope=\"col\">Phone B</th></tr></thead>\n<tbody>\n"
                + "<tr><th scope=\"row\">Battery</th><td>5000 mAh</td><td><a href=\"/shop/p/b\">Deal</a></td></tr>\n"
                + "<tr><th scope=\"row\">Weight</th><td>&lt;b&gt;190 g&lt;/b&gt;</td><td></td></tr>\n"
                + "</tbody></table></div>\n<p>After</p>\n");
    }

    @Test
    void tableColumnsAreCappedAndPlainTextDropsPipes() {
        String html = ContentMarkup.toHtml("|a|b|c|d|e|f|g|h|i|j|");
        assertThat(html).contains("<th scope=\"col\">h</th>").doesNotContain(">i<");
        assertThat(ContentMarkup.toPlainText("| Feature | A |\n|---|---|\n| Battery | 10h |"))
                .isEqualTo("Feature A Battery 10h");
    }
}
