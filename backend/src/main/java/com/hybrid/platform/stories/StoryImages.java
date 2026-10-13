package com.hybrid.platform.stories;

import com.hybrid.platform.catalog.ImageOptimizer;
import com.hybrid.platform.storage.FileStorage;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Story images. Google shows a story with its portrait poster (3:4, at least 640 x 853), so every cover is saved as a
 * 720 x 960 JPEG: portrait photos are cropped to fit, other shapes (e.g. square product photos) are shown whole on a
 * soft, blurred copy of themselves.
 */
@Component
public class StoryImages {

    static final int COVER_W = 720;
    static final int COVER_H = 960;
    private static final long MAX_PIXELS = 40_000_000L;

    private final FileStorage storage;

    public StoryImages(FileStorage storage) {
        this.storage = storage;
    }

    /** Reads a stored public image; null when Java cannot decode it (e.g. WebP) or it is unreasonably large. */
    public BufferedImage read(String key) {
        Path file = storage.resolve(FileStorage.Area.PUBLIC, key);
        try (InputStream in = Files.newInputStream(file)) {
            BufferedImage img = ImageIO.read(in);
            if (img == null || (long) img.getWidth() * img.getHeight() > MAX_PIXELS) return null;
            return img;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Saves a 720 x 960 portrait cover made from {@code source} and returns its storage key. */
    public String storeCover(BufferedImage source) throws IOException {
        byte[] jpeg = ImageOptimizer.jpeg(portrait(source), 0.86f);
        return storage.store(FileStorage.Area.PUBLIC, new ByteArrayInputStream(jpeg), "jpg", 5L * 1024 * 1024).key();
    }

    /** Copies a stored public file (e.g. a product photo) so the story owns its media and can delete it safely. */
    public FileStorage.StoredFile copy(String key, long maxBytes) throws IOException {
        Path file = storage.resolve(FileStorage.Area.PUBLIC, key);
        String name = file.getFileName().toString();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "jpg";
        try (InputStream in = Files.newInputStream(file)) {
            return storage.store(FileStorage.Area.PUBLIC, in, ext, maxBytes);
        }
    }

    static BufferedImage portrait(BufferedImage src) {
        BufferedImage out = new BufferedImage(COVER_W, COVER_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(new Color(0xF1, 0xEE, 0xE8));
            g.fillRect(0, 0, COVER_W, COVER_H);
            double ratio = src.getWidth() / (double) src.getHeight();
            if (Math.abs(ratio - COVER_W / (double) COVER_H) < 0.04) {
                g.drawImage(fit(src, COVER_W, COVER_H), 0, 0, null);
                return out;
            }
            if (ratio < COVER_W / (double) COVER_H) {
                // Taller than 3:4 (e.g. a 9:16 phone photo): crop the top and bottom.
                int h = (int) Math.round(COVER_W / ratio);
                g.drawImage(fit(src, COVER_W, h), 0, (COVER_H - h) / 2, null);
                return out;
            }
            // Wider than 3:4: a blurred, slightly darkened copy fills the frame and the whole photo sits on top.
            int bw = (int) Math.round(COVER_H * ratio);
            BufferedImage tiny = ImageOptimizer.resize(src, Math.max(1, bw / 30), Math.max(1, COVER_H / 30), false);
            BufferedImage soft = new BufferedImage(Math.max(1, bw / 6), Math.max(1, COVER_H / 6), BufferedImage.TYPE_INT_RGB);
            Graphics2D sg = soft.createGraphics();
            sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            sg.drawImage(tiny, 0, 0, soft.getWidth(), soft.getHeight(), null);
            sg.dispose();
            g.drawImage(soft, (COVER_W - bw) / 2, 0, bw, COVER_H, null);
            g.setColor(new Color(0, 0, 0, 70));
            g.fillRect(0, 0, COVER_W, COVER_H);
            int fw = COVER_W - 64;
            int fh = (int) Math.round(fw / ratio);
            BufferedImage front = fit(src, fw, fh);
            g.drawImage(front, 32, (int) Math.round(COVER_H * 0.42 - fh / 2.0), null);
            return out;
        } finally {
            g.dispose();
        }
    }

    private static BufferedImage fit(BufferedImage src, int w, int h) {
        boolean alpha = src.getColorModel().hasAlpha();
        BufferedImage scaled = ImageOptimizer.resize(src, Math.max(1, w), Math.max(1, h), alpha);
        if (!alpha) return scaled;
        BufferedImage flat = new BufferedImage(scaled.getWidth(), scaled.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = flat.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, flat.getWidth(), flat.getHeight());
        g.drawImage(scaled, 0, 0, null);
        g.dispose();
        return flat;
    }
}
