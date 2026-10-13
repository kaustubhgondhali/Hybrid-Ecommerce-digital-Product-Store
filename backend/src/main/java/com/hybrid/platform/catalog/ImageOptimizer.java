package com.hybrid.platform.catalog;

import com.hybrid.platform.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;

/**
 * Creates a grid thumbnail (max 480 px wide) and a product-page rendition (max 1200 px wide) next to every uploaded
 * product image, using only the JDK. JPEG and PNG are resized; formats the JDK cannot decode (WebP) keep just the original.
 * A rendition key is null when the original is already small enough to be used as is.
 */
@Component
public class ImageOptimizer {

    private static final Logger log = LoggerFactory.getLogger(ImageOptimizer.class);
    static final int THUMB_WIDTH = 480;
    static final int LARGE_WIDTH = 1200;
    private static final long MAX_PIXELS = 40_000_000L;

    public record Result(String thumbKey, String largeKey, Integer width, Integer height) {
    }

    private final FileStorage storage;

    public ImageOptimizer(FileStorage storage) {
        this.storage = storage;
    }

    public Result renditions(String originalKey) {
        Path file = storage.resolve(FileStorage.Area.PUBLIC, originalKey);
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) return new Result(null, null, null, null);
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if ((long) w * h > MAX_PIXELS) return new Result(null, null, w, h);
                if (w <= THUMB_WIDTH) return new Result(null, null, w, h);
                BufferedImage source = reader.read(0);
                boolean alpha = source.getColorModel().hasAlpha();
                String largeKey = w > LARGE_WIDTH ? store(source, LARGE_WIDTH, w, h, alpha, 0.85f) : null;
                String thumbKey = store(source, THUMB_WIDTH, w, h, alpha, 0.82f);
                return new Result(thumbKey, largeKey, w, h);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Image rendition generation failed for {}: {}", originalKey, e.getMessage());
            return new Result(null, null, null, null);
        }
    }

    private String store(BufferedImage source, int targetW, int w, int h, boolean alpha, float quality) throws IOException {
        BufferedImage scaled = resize(source, targetW, Math.max(1, Math.round(h * (targetW / (float) w))), alpha);
        byte[] bytes = alpha ? png(scaled) : jpeg(scaled, quality);
        return storage.store(FileStorage.Area.PUBLIC, new ByteArrayInputStream(bytes), alpha ? "png" : "jpg",
                5L * 1024 * 1024).key();
    }

    /** Halves the image repeatedly then does a final bicubic step; keeps quality close to area averaging. */
    public static BufferedImage resize(BufferedImage src, int targetW, int targetH, boolean alpha) {
        BufferedImage current = src;
        int w = src.getWidth();
        int h = src.getHeight();
        int type = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        while (w / 2 >= targetW) {
            w /= 2;
            h = Math.max(1, h / 2);
            current = draw(current, w, h, type);
        }
        return draw(current, targetW, targetH, type);
    }

    private static BufferedImage draw(BufferedImage src, int w, int h, int type) {
        BufferedImage out = new BufferedImage(w, h, type);
        Graphics2D g = out.createGraphics();
        try {
            if (type == BufferedImage.TYPE_INT_RGB) {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, w, h);
            }
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    public static byte[] jpeg(BufferedImage img, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            if (param.canWriteProgressive()) param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bytes);
        return bytes.toByteArray();
    }
}
