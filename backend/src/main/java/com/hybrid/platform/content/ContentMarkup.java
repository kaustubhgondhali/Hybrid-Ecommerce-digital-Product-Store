package com.hybrid.platform.content;

import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal, safe text format for content pages. Everything is HTML-escaped first; then:
 * blank line = new paragraph, "# " / "## " = headings, "- " = bullet list, "> " = highlighted note,
 * [link text](https://example.com or /path) = link, and lines starting with "|" = comparison table
 * (first row is the header, an optional "|---|---|" row is ignored, the first column labels each row).
 */
public final class ContentMarkup {

    private static final Pattern LINK = Pattern.compile("\\[([^\\]]{1,200})]\\(((?:https?://|/|mailto:)[^)\\s]{1,500})\\)");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\|?[\\s:|-]*-[\\s:|-]*$");
    static final int MAX_TABLE_COLUMNS = 8;
    static final int MAX_TABLE_ROWS = 60;

    private ContentMarkup() {
    }

    public static String toHtml(String body) {
        if (body == null || body.isBlank()) return "";
        StringBuilder out = new StringBuilder();
        StringBuilder para = new StringBuilder();
        List<List<String>> table = new ArrayList<>();
        boolean inList = false;
        for (String raw : body.replace("\r\n", "\n").split("\n")) {
            String line = raw.strip();
            if (line.startsWith("|")) {
                flush(out, para);
                if (inList) { out.append("</ul>\n"); inList = false; }
                if (!TABLE_SEPARATOR.matcher(line).matches() && table.size() < MAX_TABLE_ROWS) table.add(cells(line));
                continue;
            }
            flushTable(out, table);
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
        flushTable(out, table);
        flush(out, para);
        if (inList) out.append("</ul>\n");
        return out.toString();
    }

    /** Plain text with markup removed, for meta descriptions and analysis. */
    public static String toPlainText(String body) {
        if (body == null) return "";
        return LINK.matcher(body).replaceAll("$1")
                .replaceAll("(?m)^\\s*\\|?[\\s:|-]*-[\\s:|-]*$", "")
                .replaceAll("(?m)^\\s*(#{1,2}|[-*>])\\s+", "")
                .replace("|", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private static void flush(StringBuilder out, StringBuilder para) {
        if (!para.isEmpty()) {
            out.append("<p>").append(para).append("</p>\n");
            para.setLength(0);
        }
    }

    private static List<String> cells(String line) {
        String s = line.substring(1);
        if (s.endsWith("|")) s = s.substring(0, s.length() - 1);
        List<String> cells = new ArrayList<>();
        for (String c : s.split("\\|", -1)) {
            if (cells.size() == MAX_TABLE_COLUMNS) break;
            cells.add(c.strip());
        }
        return cells;
    }

    private static void flushTable(StringBuilder out, List<List<String>> table) {
        if (table.isEmpty()) return;
        int cols = table.get(0).size();
        out.append("<div class=\"table-wrap\"><table class=\"compare\">\n<thead><tr>");
        for (String h : table.get(0)) out.append("<th scope=\"col\">").append(inline(h)).append("</th>");
        out.append("</tr></thead>\n<tbody>\n");
        for (List<String> row : table.subList(1, table.size())) {
            out.append("<tr>");
            for (int i = 0; i < cols; i++) {
                String cell = i < row.size() ? inline(row.get(i)) : "";
                if (i == 0) out.append("<th scope=\"row\">").append(cell).append("</th>");
                else out.append("<td>").append(cell).append("</td>");
            }
            out.append("</tr>\n");
        }
        out.append("</tbody></table></div>\n");
        table.clear();
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
