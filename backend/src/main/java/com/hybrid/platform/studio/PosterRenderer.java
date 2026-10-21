package com.hybrid.platform.studio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Draws ready-to-post product graphics with Java's built-in 2D graphics: no design service, no API. The font (Noto
 * Sans, SIL Open Font License) ships with the application so posters look the same on every server, including
 * minimal containers without system fonts.
 */
public final class PosterRenderer {

    private static final Logger log = LoggerFactory.getLogger(PosterRenderer.class);

    public enum Format {
        SQUARE(1080, 1080, "Instagram / Facebook post"),
        PORTRAIT(1080, 1350, "Instagram portrait post"),
        STORY(1080, 1920, "Story, Reels cover, WhatsApp status"),
        PIN(1000, 1500, "Pinterest pin"),
        BANNER(1200, 628, "Facebook / LinkedIn / X link image"),
        HERO(1920, 640, "Website banner");

        public final int width;
        public final int height;
        public final String label;

        Format(int width, int height, String label) {
            this.width = width;
            this.height = height;
            this.label = label;
        }

        boolean landscape() {
            return width > height * 1.3;
        }
    }

    public enum Theme {
        LIGHT(new Color(0xF6F3EE), new Color(0x1D1D1F), new Color(0x6E6E73), new Color(0x0A7C55), new Color(0xD7263D), Color.WHITE),
        DARK(new Color(0x111318), Color.WHITE, new Color(0xB4B8C2), new Color(0x35D49A), new Color(0xFF4D5E), new Color(0x1E2129)),
        SALE(new Color(0xD7263D), Color.WHITE, new Color(0xFFE3E6), new Color(0xFFD23F), new Color(0xFFD23F), Color.WHITE);

        final Color background;
        final Color text;
        final Color muted;
        final Color accent;
        final Color badge;
        final Color card;

        Theme(Color background, Color text, Color muted, Color accent, Color badge, Color card) {
            this.background = background;
            this.text = text;
            this.muted = muted;
            this.accent = accent;
            this.badge = badge;
            this.card = card;
        }
    }

    /** What a poster shows. {@code price} and {@code mrp} are already formatted ("₹1,499"); either may be null. */
    public record Tile(String title, String tagline, String price, String mrp, Integer discountPercent, BufferedImage image) {
    }

    private static final Font REGULAR = load("/fonts/NotoSans-Regular.ttf", Font.PLAIN);
    private static final Font BOLD = load("/fonts/NotoSans-Bold.ttf", Font.BOLD);

    private PosterRenderer() {
    }

    /** One product: photo, name, one-line pitch, price with MRP and discount badge, and the store's web address. */
    public static byte[] product(Tile t, String storeName, String siteLabel, Format f, Theme theme) {
        BufferedImage out = new BufferedImage(f.width, f.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            setup(g);
            g.setColor(theme.background);
            g.fillRect(0, 0, f.width, f.height);
            int pad = Math.round(Math.min(f.width, f.height) * 0.06f);
            if (f.landscape()) {
                int imgW = Math.round(f.width * 0.42f);
                Rectangle photo = new Rectangle(f.width - imgW - pad, pad, imgW, f.height - 2 * pad);
                drawPhoto(g, t.image(), photo, theme);
                badge(g, t.discountPercent(), photo, theme, f);
                textBlock(g, t, storeName, siteLabel, new Rectangle(pad, pad, f.width - imgW - 3 * pad, f.height - 2 * pad), theme, f);
            } else {
                int textH = Math.round(f.height * (f == Format.SQUARE ? 0.45f : 0.36f));
                Rectangle photo = new Rectangle(pad, pad, f.width - 2 * pad, f.height - textH - 2 * pad);
                drawPhoto(g, t.image(), photo, theme);
                badge(g, t.discountPercent(), photo, theme, f);
                textBlock(g, t, storeName, siteLabel, new Rectangle(pad, f.height - textH, f.width - 2 * pad, textH - pad), theme, f);
            }
        } finally {
            g.dispose();
        }
        return png(out);
    }

    /** A collection or sale banner: a headline and 2 to 4 product tiles with their prices. */
    public static byte[] collection(String headline, String subline, List<Tile> tiles, String storeName, String siteLabel,
                                    Format f, Theme theme) {
        BufferedImage out = new BufferedImage(f.width, f.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            setup(g);
            g.setColor(theme.background);
            g.fillRect(0, 0, f.width, f.height);
            int pad = Math.round(Math.min(f.width, f.height) * 0.06f);
            List<Tile> list = tiles.stream().limit(4).toList();
            int headH;
            Rectangle grid;
            if (f.landscape()) {
                int textW = Math.round(f.width * 0.34f);
                headerBlock(g, headline, subline, storeName, siteLabel, new Rectangle(pad, pad, textW - pad, f.height - 2 * pad), theme, f);
                grid = new Rectangle(textW + pad, pad, f.width - textW - 2 * pad, f.height - 2 * pad);
            } else {
                headH = Math.round(f.height * 0.26f);
                headerBlock(g, headline, subline, storeName, siteLabel, new Rectangle(pad, pad, f.width - 2 * pad, headH), theme, f);
                grid = new Rectangle(pad, pad + headH + pad / 2, f.width - 2 * pad, f.height - headH - 2 * pad - pad / 2);
            }
            int n = list.size();
            int cols = f.landscape() ? n : (n <= 2 ? n : 2);
            int rows = (int) Math.ceil(n / (double) cols);
            int gap = pad / 2;
            int cw = (grid.width - gap * (cols - 1)) / Math.max(1, cols);
            int ch = (grid.height - gap * (rows - 1)) / Math.max(1, rows);
            for (int i = 0; i < n; i++) {
                int cx = grid.x + (i % cols) * (cw + gap);
                int cy = grid.y + (i / cols) * (ch + gap);
                miniTile(g, list.get(i), new Rectangle(cx, cy, cw, ch), theme);
            }
        } finally {
            g.dispose();
        }
        return png(out);
    }

    // ------------------------------------------------------------------ drawing

    private static void textBlock(Graphics2D g, Tile t, String storeName, String siteLabel, Rectangle r, Theme theme, Format f) {
        float base = Math.min(f.width, f.height) / 1080f;
        if (f.landscape()) base = f.height / 640f;
        int y = r.y;
        Font small = font(BOLD, 26 * base, storeName);
        g.setFont(small);
        g.setColor(theme.accent);
        y += g.getFontMetrics().getAscent();
        g.drawString(storeName.toUpperCase(Locale.ROOT), r.x, y);
        y += Math.round(18 * base);

        int reserved = Math.round(64 * base) + Math.round(26 * base) + Math.round(8 * base);
        if (t.price() != null) reserved += g.getFontMetrics(font(BOLD, 64 * base, t.price())).getAscent();
        int room = r.y + r.height - reserved - y;

        float titleSize = (f.landscape() ? 54 : 60) * base;
        Font title = font(BOLD, titleSize, t.title());
        FontMetrics fm = g.getFontMetrics(title);
        int lineH = fm.getAscent() + fm.getDescent() + Math.round(4 * base);
        int maxLines = Math.max(1, Math.min(f == Format.BANNER || f == Format.SQUARE ? 2 : 3, room / lineH));
        List<String> lines = wrap(g, title, t.title(), r.width, maxLines);
        g.setFont(title);
        g.setColor(theme.text);
        for (String line : lines) {
            y += fm.getAscent();
            g.drawString(line, r.x, y);
            y += fm.getDescent() + Math.round(4 * base);
        }
        room -= lines.size() * lineH;
        Font tag = font(REGULAR, 30 * base, t.tagline());
        FontMetrics tagM = g.getFontMetrics(tag);
        int tagLineH = tagM.getAscent() + tagM.getDescent();
        int tagLines = Math.min(f.landscape() || f == Format.SQUARE ? 1 : 2, (room - Math.round(10 * base)) / tagLineH);
        if (t.tagline() != null && !t.tagline().isBlank() && tagLines > 0) {
            List<String> tl = wrap(g, tag, t.tagline(), r.width, tagLines);
            g.setFont(tag);
            g.setColor(theme.muted);
            FontMetrics tm = g.getFontMetrics();
            y += Math.round(10 * base);
            for (String line : tl) {
                y += tm.getAscent();
                g.drawString(line, r.x, y);
                y += tm.getDescent();
            }
        }
        int bottom = r.y + r.height;
        Font cta = font(BOLD, 28 * base, siteLabel);
        g.setFont(cta);
        FontMetrics cm = g.getFontMetrics();
        String ctaText = "Shop now · " + siteLabel;
        int ctaW = Math.min(r.width, cm.stringWidth(ctaText) + Math.round(56 * base));
        int ctaH = Math.round(64 * base);
        int ctaY = bottom - ctaH;
        g.setColor(theme.text);
        g.fill(new RoundRectangle2D.Float(r.x, ctaY, ctaW, ctaH, ctaH, ctaH));
        g.setColor(theme.background);
        g.drawString(ellipsize(cm, ctaText, ctaW - Math.round(40 * base)), r.x + Math.round(28 * base), ctaY + (ctaH + cm.getAscent() - cm.getDescent()) / 2);

        if (t.price() != null) {
            Font price = font(BOLD, 64 * base, t.price());
            g.setFont(price);
            FontMetrics pm = g.getFontMetrics();
            int py = ctaY - Math.round(26 * base);
            int top = y + pm.getAscent() + Math.round(8 * base);
            if (py < top && f.landscape()) py = Math.min(top, ctaY - Math.round(8 * base));
            g.setColor(theme.accent);
            g.drawString(t.price(), r.x, py);
            int px = r.x + pm.stringWidth(t.price()) + Math.round(20 * base);
            if (t.mrp() != null) {
                Font mrp = font(REGULAR, 34 * base, t.mrp());
                g.setFont(mrp);
                FontMetrics mm = g.getFontMetrics();
                g.setColor(theme.muted);
                g.drawString(t.mrp(), px, py);
                int w = mm.stringWidth(t.mrp());
                int sy = py - mm.getAscent() / 3;
                g.setStroke(new BasicStroke(Math.max(2f, 3 * base)));
                g.drawLine(px, sy, px + w, sy);
            }
        }
    }

    private static void headerBlock(Graphics2D g, String headline, String subline, String storeName, String siteLabel,
                                    Rectangle r, Theme theme, Format f) {
        float base = f.landscape() ? f.height / 640f : Math.min(f.width, f.height) / 1080f;
        int y = r.y;
        g.setFont(font(BOLD, 26 * base, storeName));
        g.setColor(theme.accent);
        y += g.getFontMetrics().getAscent();
        g.drawString(storeName.toUpperCase(Locale.ROOT), r.x, y);
        y += Math.round(16 * base);
        Font h = font(BOLD, (f.landscape() ? 60 : 72) * base, headline);
        g.setFont(h);
        g.setColor(theme.text);
        FontMetrics fm = g.getFontMetrics();
        for (String line : wrap(g, h, headline, r.width, f.landscape() ? 3 : 2)) {
            y += fm.getAscent();
            g.drawString(line, r.x, y);
            y += fm.getDescent();
        }
        if (subline != null && !subline.isBlank()) {
            Font s = font(REGULAR, 32 * base, subline);
            g.setFont(s);
            g.setColor(theme.muted);
            FontMetrics sm = g.getFontMetrics();
            for (String line : wrap(g, s, subline, r.width, 2)) {
                y += sm.getAscent() + Math.round(4 * base);
                g.drawString(line, r.x, y);
            }
        }
        if (f.landscape()) {
            g.setFont(font(BOLD, 28 * base, siteLabel));
            g.setColor(theme.text);
            g.drawString(siteLabel, r.x, r.y + r.height - g.getFontMetrics().getDescent());
        }
    }

    private static void miniTile(Graphics2D g, Tile t, Rectangle r, Theme theme) {
        g.setColor(theme.card);
        g.fill(new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, 28, 28));
        int pad = Math.max(12, r.width / 20);
        Font tf = font(BOLD, Math.max(18, r.width / 15f), t.title());
        Font pf = font(BOLD, Math.max(20, r.width / 12f), t.price() == null ? "" : t.price());
        g.setFont(tf);
        int lineH = g.getFontMetrics().getHeight();
        g.setFont(pf);
        int priceH = t.price() == null ? 0 : g.getFontMetrics().getHeight();
        int textH = lineH * 2 + priceH + pad;
        Rectangle photo = new Rectangle(r.x + pad, r.y + pad, r.width - 2 * pad, Math.max(10, r.height - textH - 2 * pad));
        drawPhoto(g, t.image(), photo, null);
        if (t.discountPercent() != null && t.discountPercent() > 0) {
            String txt = "-" + t.discountPercent() + "%";
            g.setFont(font(BOLD, Math.max(16, r.width / 16f), txt));
            FontMetrics m = g.getFontMetrics();
            int bw = m.stringWidth(txt) + 20;
            int bh = m.getHeight() + 4;
            g.setColor(theme == Theme.SALE ? new Color(0xD7263D) : theme.badge);
            g.fill(new RoundRectangle2D.Float(photo.x + 8, photo.y + 8, bw, bh, bh, bh));
            g.setColor(Color.WHITE);
            g.drawString(txt, photo.x + 18, photo.y + 8 + (bh + m.getAscent() - m.getDescent()) / 2);
        }
        int y = photo.y + photo.height + pad / 2;
        Color text = theme == Theme.DARK ? Color.WHITE : new Color(0x1D1D1F);
        g.setFont(tf);
        g.setColor(text);
        FontMetrics fm = g.getFontMetrics();
        for (String line : wrap(g, tf, t.title(), r.width - 2 * pad, 2)) {
            y += fm.getAscent();
            g.drawString(line, r.x + pad, y);
            y += fm.getDescent();
        }
        if (t.price() != null) {
            g.setFont(pf);
            g.setColor(theme == Theme.DARK ? theme.accent : new Color(0x0A7C55));
            g.drawString(t.price(), r.x + pad, r.y + r.height - pad);
        }
    }

    /** The whole photo, scaled to fit, centred on a rounded white card (product photos usually have white backgrounds). */
    private static void drawPhoto(Graphics2D g, BufferedImage img, Rectangle r, Theme theme) {
        if (theme != null) {
            g.setColor(Color.WHITE);
            g.fill(new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, 36, 36));
        }
        if (img == null) {
            g.setColor(new Color(0xD2D2D7));
            g.setFont(font(REGULAR, Math.max(16, r.width / 22f), "Photo"));
            String s = "Add a product photo";
            FontMetrics m = g.getFontMetrics();
            g.drawString(s, r.x + (r.width - m.stringWidth(s)) / 2, r.y + r.height / 2);
            return;
        }
        int inset = Math.round(Math.min(r.width, r.height) * 0.05f);
        double scale = Math.min((r.width - 2.0 * inset) / img.getWidth(), (r.height - 2.0 * inset) / img.getHeight());
        int w = Math.max(1, (int) Math.round(img.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(img.getHeight() * scale));
        g.drawImage(img, r.x + (r.width - w) / 2, r.y + (r.height - h) / 2, w, h, null);
    }

    private static void badge(Graphics2D g, Integer discount, Rectangle photo, Theme theme, Format f) {
        if (discount == null || discount <= 0) return;
        float base = Math.min(f.width, f.height) / 1080f;
        if (f.landscape()) base = f.height / 640f;
        int d = Math.round(170 * base);
        int x = photo.x + photo.width - d - Math.round(20 * base);
        int y = photo.y + Math.round(20 * base);
        g.setColor(theme == Theme.SALE ? new Color(0x1D1D1F) : theme.badge);
        g.fill(new Ellipse2D.Float(x, y, d, d));
        Color fg = theme == Theme.SALE ? new Color(0xFFD23F) : Color.WHITE;
        g.setColor(fg);
        String pct = discount + "%";
        g.setFont(font(BOLD, 52 * base, pct));
        FontMetrics m = g.getFontMetrics();
        g.drawString(pct, x + (d - m.stringWidth(pct)) / 2, y + d / 2 + Math.round(4 * base));
        g.setFont(font(BOLD, 28 * base, "OFF"));
        FontMetrics m2 = g.getFontMetrics();
        g.drawString("OFF", x + (d - m2.stringWidth("OFF")) / 2, y + d / 2 + m2.getAscent() + Math.round(4 * base));
    }

    // ------------------------------------------------------------------ helpers

    static List<String> wrap(Graphics2D g, Font font, String text, int width, int maxLines) {
        FontMetrics fm = g.getFontMetrics(font);
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        String[] words = text.strip().split("\\s+");
        for (int i = 0; i < words.length; i++) {
            String candidate = line.isEmpty() ? words[i] : line + " " + words[i];
            if (fm.stringWidth(candidate) <= width || line.isEmpty()) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            lines.add(line.toString());
            line.setLength(0);
            line.append(words[i]);
            if (lines.size() == maxLines) {
                String rest = String.join(" ", java.util.Arrays.copyOfRange(words, i, words.length));
                lines.set(maxLines - 1, ellipsize(fm, lines.get(maxLines - 1) + " " + rest, width));
                return lines;
            }
        }
        if (!line.isEmpty()) lines.add(ellipsize(fm, line.toString(), width));
        return lines;
    }

    static String ellipsize(FontMetrics fm, String s, int width) {
        if (fm.stringWidth(s) <= width) return s;
        String t = s;
        while (t.length() > 1 && fm.stringWidth(t + "…") > width) t = t.substring(0, t.length() - 1);
        return t.strip() + "…";
    }

    /** The bundled font when it has every character (e.g. ₹), otherwise Java's logical font (covers Indic scripts). */
    private static Font font(Font base, float size, String text) {
        Font f = base.deriveFont(size);
        if (text == null || f.canDisplayUpTo(text) == -1) return f;
        return new Font(Font.SANS_SERIF, base == BOLD ? Font.BOLD : Font.PLAIN, Math.round(size));
    }

    private static void setup(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    private static byte[] png(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Font load(String path, int style) {
        try (InputStream in = PosterRenderer.class.getResourceAsStream(path)) {
            if (in != null) return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (IOException | FontFormatException e) {
            log.warn("Could not load {}: {}", path, e.getMessage());
        }
        return new Font(Font.SANS_SERIF, style, 12);
    }
}
