package com.hybrid.platform.content;

import org.springframework.web.util.HtmlUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal, safe text format for content pages. Everything is HTML-escaped first; then:
 * blank line = new paragraph, "# " / "## " = headings, "- " = bullet list, "> " = highlighted note,
 * [link text](https://example.com or /path) = link.
 */
public final class ContentMarkup {

    private static final Pattern LINK = Pattern.compile("\\[([^\\]]{1,200})]\\(((?:https?://|/|mailto:)[^)\\s]{1,500})\\)");

    private ContentMarkup() {
    }

    public static String toHtml(String body) {
        if (body == null || body.isBlank()) return "";
        StringBuilder out = new StringBuilder();
        StringBuilder para = new StringBuilder();
        boolean inList = false;
        for (String raw : body.replace("\r\n", "\n").split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                flush(out, para);
                if (inList) { out.append("</ul>\n"); inList = false; }
                continue;
            }
            if (line.startsWith("> ")) {
                flush(out, para);
                if (inList) { out.append("</ul>\n"); inList = false; }
                out.append("<p class=\"note\">").append(inline(line.substring(2))).append("</p>\n");
            } else if (line.startsWith("## ") || line.startsWith("# ")) {
                flush(out, para);
                if (inList) { out.append("</ul>\n"); inList = false; }
                String tag = line.startsWith("## ") ? "h3" : "h2";
                out.append('<').append(tag).append('>').append(inline(line.substring(tag.equals("h3") ? 3 : 2)))
                        .append("</").append(tag).append(">\n");
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                flush(out, para);
                if (!inList) { out.append("<ul>\n"); inList = true; }
                out.append("<li>").append(inline(line.substring(2))).append("</li>\n");
            } else {
                if (inList) { out.append("</ul>\n"); inList = false; }
                if (!para.isEmpty()) para.append("<br>");
                para.append(inline(line));
            }
        }
        flush(out, para);
        if (inList) out.append("</ul>\n");
        return out.toString();
    }

    /** Plain text with markup removed, for meta descriptions and analysis. */
    public static String toPlainText(String body) {
        if (body == null) return "";
        return LINK.matcher(body).replaceAll("$1")
                .replaceAll("(?m)^\\s*(#{1,2}|[-*>])\\s+", "")
                .replaceAll("\\s+", " ").trim();
    }

    private static void flush(StringBuilder out, StringBuilder para) {
        if (!para.isEmpty()) {
            out.append("<p>").append(para).append("</p>\n");
            para.setLength(0);
        }
    }

    private static String inline(String text) {
        String escaped = HtmlUtils.htmlEscape(text);
        Matcher m = LINK.matcher(escaped);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String href = m.group(2);
            boolean external = href.startsWith("http");
            m.appendReplacement(sb, Matcher.quoteReplacement("<a href=\"" + href + "\""
                    + (external ? " rel=\"noopener\" target=\"_blank\"" : "") + ">" + m.group(1) + "</a>"));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
