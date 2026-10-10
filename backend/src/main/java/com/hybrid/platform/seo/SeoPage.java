package com.hybrid.platform.seo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Everything injected into a page shell: head tags (title, meta, canonical, Open Graph, JSON-LD) and body HTML. */
public final class SeoPage {

    private String title;
    private String description;
    private String canonical;
    private boolean noindex;
    private String ogType = "website";
    private String image;
    private String siteName;
    private final List<Map<String, Object>> jsonLd = new ArrayList<>();
    private final List<String[]> extraMeta = new ArrayList<>();
    private String bodyHtml = "";

    public SeoPage title(String v) { this.title = v; return this; }
    public SeoPage description(String v) { this.description = v; return this; }
    public SeoPage canonical(String v) { this.canonical = v; return this; }
    public SeoPage noindex(boolean v) { this.noindex = v; return this; }
    public SeoPage ogType(String v) { this.ogType = v; return this; }
    public SeoPage image(String v) { this.image = v; return this; }
    public SeoPage siteName(String v) { this.siteName = v; return this; }
    public SeoPage jsonLd(Map<String, Object> v) { this.jsonLd.add(v); return this; }
    public SeoPage meta(String name, String content) { if (content != null && !content.isBlank()) extraMeta.add(new String[]{name, content}); return this; }
    public SeoPage body(String html) { this.bodyHtml = html == null ? "" : html; return this; }

    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getCanonical() { return canonical; }
    public boolean isNoindex() { return noindex; }

    /** Renders into the shell: replaces &lt;title&gt; with the head block and the &lt;!--SSR--&gt; marker with body HTML. */
    public String renderInto(String shell, ObjectMapper mapper) {
        StringBuilder head = new StringBuilder();
        head.append("<title>").append(esc(title)).append("</title>\n");
        if (description != null) head.append("  <meta name=\"description\" content=\"").append(esc(description)).append("\">\n");
        head.append("  <meta name=\"robots\" content=\"").append(noindex ? "noindex, follow" : "index, follow, max-image-preview:large").append("\">\n");
        if (canonical != null && !noindex) head.append("  <link rel=\"canonical\" href=\"").append(esc(canonical)).append("\">\n");
        for (String[] m : extraMeta) {
            head.append("  <meta name=\"").append(esc(m[0])).append("\" content=\"").append(esc(m[1])).append("\">\n");
        }
        og(head, "og:type", ogType);
        og(head, "og:title", title);
        og(head, "og:description", description);
        og(head, "og:url", canonical);
        og(head, "og:site_name", siteName);
        og(head, "og:image", image);
        og(head, "og:locale", "en_IN");
        head.append("  <meta name=\"twitter:card\" content=\"").append(image != null ? "summary_large_image" : "summary").append("\">\n");
        head.append("  <meta name=\"twitter:title\" content=\"").append(esc(title)).append("\">\n");
        if (description != null) head.append("  <meta name=\"twitter:description\" content=\"").append(esc(description)).append("\">\n");
        if (image != null) head.append("  <meta name=\"twitter:image\" content=\"").append(esc(image)).append("\">\n");
        for (Map<String, Object> ld : jsonLd) {
            head.append("  <script type=\"application/ld+json\">").append(json(ld, mapper)).append("</script>\n");
        }
        String out = shell.replaceFirst("(?s)<title>.*?</title>", java.util.regex.Matcher.quoteReplacement(head.toString().stripTrailing()));
        return out.replace("<!--SSR-->", bodyHtml);
    }

    private static void og(StringBuilder head, String property, String content) {
        if (content == null || content.isBlank()) return;
        head.append("  <meta property=\"").append(property).append("\" content=\"").append(esc(content)).append("\">\n");
    }

    /** JSON safe to embed inside a script element. */
    static String json(Object value, ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(value).replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String esc(String v) {
        return v == null ? "" : HtmlUtils.htmlEscape(v);
    }
}
