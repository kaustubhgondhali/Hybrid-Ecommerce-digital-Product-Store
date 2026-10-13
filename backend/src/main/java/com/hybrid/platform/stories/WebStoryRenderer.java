package com.hybrid.platform.stories;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes a story as a valid AMP Web Story (the format Google shows in Search, Discover and Images): an automatic cover
 * page, the team's pages, and a closing page that links back to the store. Product pages get a swipe-up link to the
 * product page on this site (never the partner link) and the current price, so prices never go stale inside a story.
 */
@Component
public class WebStoryRenderer {

    /** What a product page of the story links to, with the price line shown on it (null when prices are hidden). */
    public record ProductLink(String url, String priceText) {
    }

    /** Everything needed to render one story. URLs in {@code logoUrl}, {@code homeUrl}, {@code canonical} are absolute. */
    public record Model(WebStory story, String storeName, String logoUrl, String homeUrl, String canonical,
                        String accent, String accentText, String headingFont, boolean preview,
                        Map<Long, ProductLink> products, String baseUrl) {
    }

    private static final String BOILERPLATE = "<style amp-boilerplate>body{-webkit-animation:-amp-start 8s steps(1,end) 0s 1 normal both;"
            + "-moz-animation:-amp-start 8s steps(1,end) 0s 1 normal both;-ms-animation:-amp-start 8s steps(1,end) 0s 1 normal both;"
            + "animation:-amp-start 8s steps(1,end) 0s 1 normal both}@-webkit-keyframes -amp-start{from{visibility:hidden}to{visibility:visible}}"
            + "@-moz-keyframes -amp-start{from{visibility:hidden}to{visibility:visible}}@-ms-keyframes -amp-start{from{visibility:hidden}to{visibility:visible}}"
            + "@-o-keyframes -amp-start{from{visibility:hidden}to{visibility:visible}}@keyframes -amp-start{from{visibility:hidden}to{visibility:visible}}</style>"
            + "<noscript><style amp-boilerplate>body{-webkit-animation:none;-moz-animation:none;-ms-animation:none;animation:none}</style></noscript>";

    private static final String CSS = """
            amp-story{font-family:"Segoe UI Variable Text","Segoe UI",system-ui,-apple-system,Roboto,Arial,sans-serif;color:#fff}
            amp-story-page{background:#111}
            .backdrop img{object-fit:cover;filter:blur(28px) brightness(.72);transform:scale(1.15)}
            .whole img{object-fit:contain}
            .cover-fill img,.fill img{object-fit:cover}
            .scrim{background:linear-gradient(to top,rgba(0,0,0,.82) 0%,rgba(0,0,0,.55) 32%,rgba(0,0,0,0) 62%);align-content:end;padding:28px 24px 64px}
            .scrim.top-space{padding-bottom:96px}
            .brand{display:flex;align-items:center;gap:10px;font-weight:700;font-size:15px;letter-spacing:.02em;margin-bottom:14px}
            .brand amp-img{border-radius:10px;background:#fff}
            h1{font-size:34px;line-height:1.12;font-weight:800;margin:0 0 10px;text-shadow:0 2px 12px rgba(0,0,0,.35)}
            h2{font-size:27px;line-height:1.16;font-weight:800;margin:0 0 10px;text-shadow:0 2px 12px rgba(0,0,0,.35)}
            p{font-size:17px;line-height:1.45;margin:0 0 10px;color:rgba(255,255,255,.92)}
            .chip{display:inline-block;margin-top:4px;padding:7px 14px;border-radius:999px;font-weight:700;font-size:15px}
            .eyebrow{text-transform:uppercase;letter-spacing:.12em;font-size:12px;font-weight:700;opacity:.85;margin-bottom:8px}
            .end{align-content:center;justify-items:center;text-align:center;padding:32px}
            .end amp-img{border-radius:22px;background:#fff;margin-bottom:18px}
            """;

    private final ObjectMapper mapper;

    public WebStoryRenderer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String render(Model m) {
        WebStory s = m.story();
        boolean anyVideo = s.getPages().stream().anyMatch(WebStoryPage::isVideo);
        String cover = abs(m.baseUrl(), s.coverUrl());
        StringBuilder h = new StringBuilder(8192);
        h.append("<!doctype html>\n<html amp lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n");
        h.append("<title>").append(esc(s.getTitle())).append(" | ").append(esc(m.storeName())).append("</title>\n");
        h.append("<link rel=\"canonical\" href=\"").append(esc(m.canonical())).append("\">\n");
        h.append("<meta name=\"viewport\" content=\"width=device-width,minimum-scale=1,initial-scale=1\">\n");
        if (s.getDescription() != null) h.append("<meta name=\"description\" content=\"").append(esc(s.getDescription())).append("\">\n");
        h.append("<meta name=\"robots\" content=\"").append(m.preview() ? "noindex, nofollow" : "index, follow, max-image-preview:large").append("\">\n");
        meta(h, "og:type", "article");
        meta(h, "og:title", s.getTitle());
        meta(h, "og:description", s.getDescription());
        meta(h, "og:url", m.canonical());
        meta(h, "og:site_name", m.storeName());
        meta(h, "og:image", cover);
        h.append("<meta name=\"twitter:card\" content=\"summary_large_image\">\n");
        h.append("<link rel=\"icon\" type=\"image/png\" href=\"/assets/img/favicon.png\">\n");
        h.append("<script async src=\"https://cdn.ampproject.org/v0.js\"></script>\n");
        h.append("<script async custom-element=\"amp-story\" src=\"https://cdn.ampproject.org/v0/amp-story-1.0.js\"></script>\n");
        if (anyVideo) h.append("<script async custom-element=\"amp-video\" src=\"https://cdn.ampproject.org/v0/amp-video-0.1.js\"></script>\n");
        h.append(BOILERPLATE).append('\n');
        h.append("<style amp-custom>").append(CSS.replace("\n", ""))
                .append("h1,h2{font-family:").append(m.headingFont()).append("}.chip{background:").append(m.accent())
                .append(";color:").append(m.accentText()).append("}</style>\n");
        h.append("<script type=\"application/ld+json\">").append(json(articleLd(m, cover))).append("</script>\n");
        h.append("</head>\n<body>\n");

        h.append("<amp-story standalone title=\"").append(esc(s.getTitle())).append("\" publisher=\"").append(esc(m.storeName()))
                .append("\" publisher-logo-src=\"").append(esc(m.logoUrl())).append("\" poster-portrait-src=\"").append(esc(cover)).append("\">\n");

        // ---- cover page
        h.append("<amp-story-page id=\"cover\">\n<amp-story-grid-layer template=\"fill\">")
                .append("<amp-img class=\"cover-fill\" src=\"").append(esc(s.coverUrl())).append("\" layout=\"fill\" alt=\"")
                .append(esc(s.getTitle())).append("\"></amp-img></amp-story-grid-layer>\n");
        h.append("<amp-story-grid-layer template=\"vertical\" class=\"scrim\"><div class=\"brand\" animate-in=\"fade-in\">")
                .append("<amp-img src=\"").append(esc(m.logoUrl())).append("\" width=\"36\" height=\"36\" layout=\"fixed\" alt=\"\"></amp-img><span>")
                .append(esc(m.storeName())).append("</span></div>")
                .append("<h1 animate-in=\"fly-in-bottom\">").append(esc(s.getTitle())).append("</h1>");
        if (s.getDescription() != null) {
            h.append("<p animate-in=\"fade-in\" animate-in-delay=\"0.4s\">").append(esc(s.getDescription())).append("</p>");
        }
        h.append("</amp-story-grid-layer>\n</amp-story-page>\n");

        // ---- the team's pages
        int n = 0;
        for (WebStoryPage p : s.getPages()) {
            n++;
            String id = "page-" + n;
            ProductLink link = p.getProductId() == null ? null : m.products().get(p.getProductId());
            h.append("<amp-story-page id=\"").append(id).append('"');
            if (p.isVideo()) h.append(" auto-advance-after=\"").append(id).append("-video\"");
            h.append(">\n");
            String alt = p.getAltText() != null ? p.getAltText() : p.getHeadline() != null ? p.getHeadline() : s.getTitle();
            if (p.isVideo()) {
                h.append("<amp-story-grid-layer template=\"fill\"><amp-video id=\"").append(id).append("-video\" autoplay layout=\"fill\" poster=\"")
                        .append(esc(p.posterUrl())).append("\" title=\"").append(esc(alt)).append("\"><source src=\"").append(esc(p.mediaUrl()))
                        .append("\" type=\"").append(p.videoType()).append("\"></amp-video></amp-story-grid-layer>\n");
            } else if (p.showWhole()) {
                h.append("<amp-story-grid-layer template=\"fill\"><amp-img class=\"backdrop\" src=\"").append(esc(p.mediaUrl()))
                        .append("\" layout=\"fill\" alt=\"\"></amp-img></amp-story-grid-layer>\n");
                h.append("<amp-story-grid-layer template=\"fill\"><amp-img class=\"whole\" src=\"").append(esc(p.mediaUrl()))
                        .append("\" layout=\"fill\" alt=\"").append(esc(alt)).append("\"></amp-img></amp-story-grid-layer>\n");
            } else {
                h.append("<amp-story-grid-layer template=\"fill\"><amp-img class=\"fill\" src=\"").append(esc(p.mediaUrl()))
                        .append("\" layout=\"fill\" alt=\"").append(esc(alt)).append("\"></amp-img></amp-story-grid-layer>\n");
            }
            boolean text = p.getHeadline() != null || p.getBody() != null || (link != null && link.priceText() != null);
            if (text) {
                h.append("<amp-story-grid-layer template=\"vertical\" class=\"scrim").append(link != null ? " top-space" : "").append("\">");
                if (p.getHeadline() != null) h.append("<h2 animate-in=\"fly-in-bottom\">").append(esc(p.getHeadline())).append("</h2>");
                if (p.getBody() != null) {
                    h.append("<p animate-in=\"fade-in\" animate-in-delay=\"0.3s\">").append(esc(p.getBody())).append("</p>");
                }
                if (link != null && link.priceText() != null) {
                    h.append("<div><span class=\"chip\" animate-in=\"fade-in\" animate-in-delay=\"0.5s\">").append(esc(link.priceText())).append("</span></div>");
                }
                h.append("</amp-story-grid-layer>\n");
            }
            if (link != null) outlink(h, m, link.url(), p.getCtaLabel() != null ? p.getCtaLabel() : "See the deal");
            h.append("</amp-story-page>\n");
        }

        // ---- closing page back to the store
        h.append("<amp-story-page id=\"more\">\n<amp-story-grid-layer template=\"fill\"><amp-img class=\"backdrop\" src=\"")
                .append(esc(s.coverUrl())).append("\" layout=\"fill\" alt=\"\"></amp-img></amp-story-grid-layer>\n");
        h.append("<amp-story-grid-layer template=\"vertical\" class=\"end\"><amp-img src=\"").append(esc(m.logoUrl()))
                .append("\" width=\"96\" height=\"96\" layout=\"fixed\" alt=\"").append(esc(m.storeName())).append("\"></amp-img>")
                .append("<div class=\"eyebrow\">").append(esc(m.storeName())).append("</div>")
                .append("<h2 animate-in=\"fly-in-bottom\">Find more hand-picked deals</h2>")
                .append("<p animate-in=\"fade-in\" animate-in-delay=\"0.3s\">New offers and price drops every day.</p></amp-story-grid-layer>\n");
        outlink(h, m, m.homeUrl(), "Visit " + m.storeName());
        h.append("</amp-story-page>\n");

        h.append("</amp-story>\n</body>\n</html>\n");
        return h.toString();
    }

    private static void outlink(StringBuilder h, Model m, String href, String label) {
        h.append("<amp-story-page-outlink layout=\"nodisplay\" theme=\"custom\" cta-accent-element=\"background\" cta-accent-color=\"")
                .append(esc(m.accent())).append("\"><a href=\"").append(esc(href)).append("\">").append(esc(label)).append("</a></amp-story-page-outlink>\n");
    }

    private Map<String, Object> articleLd(Model m, String cover) {
        WebStory s = m.story();
        Map<String, Object> org = new LinkedHashMap<>();
        org.put("@type", "Organization");
        org.put("name", m.storeName());
        org.put("logo", Map.of("@type", "ImageObject", "url", m.logoUrl()));
        Map<String, Object> ld = new LinkedHashMap<>();
        ld.put("@context", "https://schema.org");
        ld.put("@type", "Article");
        ld.put("headline", s.getTitle());
        if (s.getDescription() != null) ld.put("description", s.getDescription());
        if (cover != null) ld.put("image", java.util.List.of(cover));
        if (s.getPublishedAt() != null) ld.put("datePublished", s.getPublishedAt().truncatedTo(ChronoUnit.SECONDS).toString());
        ld.put("dateModified", s.getUpdatedAt().truncatedTo(ChronoUnit.SECONDS).toString());
        ld.put("author", Map.of("@type", "Organization", "name", m.storeName(), "url", m.homeUrl()));
        ld.put("publisher", org);
        ld.put("mainEntityOfPage", m.canonical());
        return ld;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value).replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void meta(StringBuilder h, String property, String content) {
        if (content == null || content.isBlank()) return;
        h.append("<meta property=\"").append(property).append("\" content=\"").append(esc(content)).append("\">\n");
    }

    static String abs(String base, String path) {
        return path == null ? null : path.startsWith("http") ? path : base + path;
    }

    static String esc(String v) {
        return v == null ? "" : HtmlUtils.htmlEscape(v);
    }
}
