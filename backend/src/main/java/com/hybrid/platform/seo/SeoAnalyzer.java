package com.hybrid.platform.seo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** On-page SEO checks for a product (or any page) with a 0-100 score, in the spirit of Yoast/RankMath. */
public final class SeoAnalyzer {

    public enum Status { PASS, WARN, FAIL }

    public record Check(String id, String label, Status status, String message, int weight) {
    }

    public record Analysis(int score, String grade, List<Check> checks, Serp serp) {
        public long problems() {
            return checks.stream().filter(c -> c.status() != Status.PASS).count();
        }
    }

    /** What Google is likely to show. */
    public record Serp(String title, String url, String description, boolean titleTruncated, boolean descriptionTruncated) {
    }

    /** Input describing the page as it would be rendered. */
    public record Input(String effectiveTitle, String metaDescription, String autoDescription,
                        String focusKeyword, String slug, String url, String summary, String description,
                        int imageCount, int imagesMissingAlt, boolean hasCategory, boolean hasBrand, boolean hasPrice,
                        long approvedReviews, boolean noindex, boolean duplicateTitle, boolean duplicateDescription,
                        boolean published) {
    }

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    private SeoAnalyzer() {
    }

    public static Analysis analyze(Input in) {
        List<Check> checks = new ArrayList<>();
        String desc = in.metaDescription() != null ? in.metaDescription() : in.autoDescription();
        String body = ((in.summary() == null ? "" : in.summary()) + " " + (in.description() == null ? "" : in.description())).trim();
        int words = countWords(body);
        String kw = in.focusKeyword() == null ? null : in.focusKeyword().trim().toLowerCase(Locale.ROOT);
        if (kw != null && kw.isEmpty()) kw = null;

        // --- title
        int tl = len(in.effectiveTitle());
        if (tl >= 30 && tl <= 60) checks.add(pass("title-length", "SEO title length", "Title is " + tl + " characters (ideal 30-60).", 10));
        else if (tl > 60 && tl <= 70) checks.add(warn("title-length", "SEO title length", "Title is " + tl + " characters; Google may cut it after ~60. Shorten it.", 10));
        else if (tl < 30) checks.add(warn("title-length", "SEO title length", "Title is only " + tl + " characters. Use 30-60 characters and include what the product is.", 10));
        else checks.add(fail("title-length", "SEO title length", "Title is " + tl + " characters and will be truncated. Keep it under 60.", 10));

        // --- meta description
        int dl = len(desc);
        if (in.metaDescription() == null) {
            checks.add(warn("meta-description", "Meta description", "No custom meta description; one is generated from the summary. Write a persuasive 120-160 character description.", 10));
        } else if (dl >= 120 && dl <= 160) {
            checks.add(pass("meta-description", "Meta description", "Meta description is " + dl + " characters (ideal 120-160).", 10));
        } else if (dl >= 70 && dl <= 200) {
            checks.add(warn("meta-description", "Meta description", "Meta description is " + dl + " characters; aim for 120-160.", 10));
        } else {
            checks.add(fail("meta-description", "Meta description", "Meta description is " + dl + " characters; aim for 120-160.", 10));
        }

        // --- focus keyword
        if (kw == null) {
            checks.add(warn("keyword", "Focus keyword", "Set a focus keyword (the main phrase shoppers search for) to unlock keyword checks.", 8));
        } else {
            checks.add(pass("keyword", "Focus keyword", "Focus keyword: \u201C" + in.focusKeyword().trim() + "\u201D.", 8));
            boolean inTitle = contains(in.effectiveTitle(), kw);
            checks.add(inTitle ? pass("kw-title", "Keyword in title", "The SEO title contains the focus keyword.", 8)
                    : fail("kw-title", "Keyword in title", "Add the focus keyword to the SEO title, ideally near the start.", 8));
            boolean inDesc = contains(desc, kw);
            checks.add(inDesc ? pass("kw-description", "Keyword in meta description", "The meta description contains the focus keyword.", 5)
                    : warn("kw-description", "Keyword in meta description", "Use the focus keyword in the meta description; Google highlights it in results.", 5));
            String slugWords = in.slug() == null ? "" : in.slug().replace('-', ' ');
            boolean inSlug = contains(slugWords, kw);
            checks.add(inSlug ? pass("kw-slug", "Keyword in URL", "The URL contains the focus keyword.", 5)
                    : warn("kw-slug", "Keyword in URL", "Consider a URL that contains the focus keyword (old URLs are redirected automatically).", 5));
            String intro = firstWords(body, 100);
            boolean inIntro = contains(intro, kw);
            checks.add(inIntro ? pass("kw-intro", "Keyword in introduction", "The focus keyword appears in the first 100 words.", 5)
                    : warn("kw-intro", "Keyword in introduction", "Mention the focus keyword in the summary or first paragraph.", 5));
            if (words >= 50) {
                double density = occurrences(body, kw) * countWords(kw) * 100.0 / words;
                String d = String.format(Locale.ROOT, "%.1f", density);
                if (density >= 0.5 && density <= 3.0) checks.add(pass("kw-density", "Keyword density", "Keyword density is " + d + "% (ideal 0.5-3%).", 4));
                else if (density > 3.0) checks.add(fail("kw-density", "Keyword density", "Keyword density is " + d + "%; this looks like keyword stuffing. Write naturally.", 4));
                else checks.add(warn("kw-density", "Keyword density", "Keyword density is " + d + "%; use the keyword a few more times naturally.", 4));
            }
        }

        // --- content
        if (words >= 150) checks.add(pass("content-length", "Content length", words + " words of product content. Good.", 10));
        else if (words >= 50) checks.add(warn("content-length", "Content length", "Only " + words + " words. Aim for 150+ words: features, specs, use cases, what is in the box.", 10));
        else checks.add(fail("content-length", "Content length", "Only " + words + " words. Thin pages rarely rank; write at least 150 words.", 10));

        checks.add(in.summary() != null && !in.summary().isBlank()
                ? pass("summary", "Summary", "A short summary is set.", 3)
                : warn("summary", "Summary", "Add a one-line summary; it is shown in listings and used for the description fallback.", 3));

        // --- images
        if (in.imageCount() == 0) checks.add(fail("images", "Product images", "No images. Products without images get far fewer clicks and no image search traffic.", 8));
        else if (in.imageCount() == 1) checks.add(warn("images", "Product images", "Only one image. Add more angles and close-ups.", 8));
        else checks.add(pass("images", "Product images", in.imageCount() + " images.", 8));
        if (in.imageCount() > 0) {
            checks.add(in.imagesMissingAlt() == 0
                    ? pass("image-alt", "Image alt text", "All images have alt text.", 5)
                    : warn("image-alt", "Image alt text", in.imagesMissingAlt() + " image(s) have no alt text. Describe each image (helps image search and accessibility).", 5));
        }

        // --- URL
        int slugLen = len(in.slug());
        int slugWords = in.slug() == null ? 0 : in.slug().split("-").length;
        if (slugLen <= 60 && slugWords <= 8) checks.add(pass("slug", "URL", "Short, readable URL.", 4));
        else checks.add(warn("slug", "URL", "URL is long (" + slugLen + " characters). Keep it short and descriptive.", 4));

        // --- uniqueness
        checks.add(in.duplicateTitle()
                ? fail("duplicate-title", "Unique title", "Another product uses the same SEO title. Make each title unique.", 6)
                : pass("duplicate-title", "Unique title", "No other product uses this title.", 6));
        checks.add(in.duplicateDescription()
                ? fail("duplicate-description", "Unique description", "Another product has the same meta description.", 4)
                : pass("duplicate-description", "Unique description", "The meta description is unique.", 4));

        // --- structured data / rich results
        checks.add(in.hasPrice()
                ? pass("rich-price", "Price in rich results", "Price and availability are published as structured data.", 4)
                : warn("rich-price", "Price in rich results", "No price, so Google cannot show price/availability in results.", 4));
        checks.add(in.hasBrand()
                ? pass("brand", "Brand", "Brand is set (used in structured data).", 2)
                : warn("brand", "Brand", "Set a brand; it is part of Google's product rich results.", 2));
        checks.add(in.hasCategory()
                ? pass("category", "Category", "Product is in a category (adds breadcrumbs and internal links).", 3)
                : warn("category", "Category", "Put the product in a category for breadcrumbs and internal linking.", 3));
        checks.add(in.approvedReviews() > 0
                ? pass("reviews", "Reviews", in.approvedReviews() + " approved review(s): star ratings can show in results.", 3)
                : warn("reviews", "Reviews", "No approved reviews yet. Star ratings appear in Google once customers review the product.", 3));

        // --- indexability
        if (in.noindex()) checks.add(fail("indexable", "Indexable", "\u201CHide from search engines\u201D is on, so this product will not appear in Google.", 8));
        else if (!in.published()) checks.add(warn("indexable", "Indexable", "The product is not published yet, so search engines cannot see it.", 8));
        else checks.add(pass("indexable", "Indexable", "Search engines can index this product and it is in the sitemap.", 8));

        double total = 0, got = 0;
        for (Check c : checks) {
            total += c.weight();
            got += c.weight() * (c.status() == Status.PASS ? 1 : c.status() == Status.WARN ? 0.5 : 0);
        }
        int score = total == 0 ? 0 : (int) Math.round(got * 100 / total);
        return new Analysis(score, grade(score), checks, serp(in.effectiveTitle(), in.url(), desc));
    }

    public static Serp serp(String title, String url, String description) {
        String t = title == null ? "" : title;
        String d = description == null ? "" : description;
        boolean tCut = t.length() > 60;
        boolean dCut = d.length() > 160;
        return new Serp(tCut ? cut(t, 60) : t, url, dCut ? cut(d, 160) : d, tCut, dCut);
    }

    public static String grade(int score) {
        return score >= 80 ? "Good" : score >= 50 ? "Needs work" : "Poor";
    }

    static int countWords(String text) {
        if (text == null) return 0;
        var m = WORD.matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    static int occurrences(String text, String phrase) {
        if (text == null || phrase == null || phrase.isEmpty()) return 0;
        String hay = " " + normalize(text) + " ";
        String needle = " " + normalize(phrase) + " ";
        int n = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length() - 1;
        }
        return n;
    }

    private static boolean contains(String text, String kw) {
        return occurrences(text, kw) > 0;
    }

    private static String normalize(String s) {
        return String.join(" ", WORD.matcher(s.toLowerCase(Locale.ROOT)).results().map(r -> r.group()).toList());
    }

    private static String firstWords(String text, int n) {
        var m = WORD.matcher(text);
        int count = 0, end = 0;
        while (m.find() && count < n) {
            count++;
            end = m.end();
        }
        return text.substring(0, end);
    }

    private static String cut(String s, int max) {
        int i = s.lastIndexOf(' ', max - 1);
        return (i > max / 2 ? s.substring(0, i) : s.substring(0, max - 1)) + " \u2026";
    }

    private static int len(String s) {
        return s == null ? 0 : s.trim().length();
    }

    private static Check pass(String id, String label, String msg, int w) { return new Check(id, label, Status.PASS, msg, w); }
    private static Check warn(String id, String label, String msg, int w) { return new Check(id, label, Status.WARN, msg, w); }
    private static Check fail(String id, String label, String msg, int w) { return new Check(id, label, Status.FAIL, msg, w); }
}
