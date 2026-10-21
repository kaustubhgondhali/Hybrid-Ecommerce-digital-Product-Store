package com.hybrid.platform.studio;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PosterRendererTest {

    private static final Path OUT = Path.of("target", "posters");

    private static BufferedImage photo() {
        BufferedImage img = new BufferedImage(800, 800, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 800, 800);
        g.setColor(new Color(0x2B6CB0));
        g.fillRoundRect(200, 150, 400, 500, 80, 80);
        g.setColor(new Color(0x1A202C));
        g.fillOval(300, 250, 200, 200);
        g.dispose();
        return img;
    }

    @Test
    void rendersEveryFormatAtItsExactSizeWithRupeeSign() throws Exception {
        Files.createDirectories(OUT);
        PosterRenderer.Tile tile = new PosterRenderer.Tile("Boat Airdopes 141 Bluetooth Truly Wireless Earbuds with 42H Playtime",
                "42 hours of playback with ENx noise cancellation", "₹1,099", "₹4,490", 76, photo());
        for (PosterRenderer.Format f : PosterRenderer.Format.values()) {
            for (PosterRenderer.Theme t : PosterRenderer.Theme.values()) {
                byte[] png = PosterRenderer.product(tile, "Elaashop", "elaashop.in", f, t);
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
                assertThat(img.getWidth()).isEqualTo(f.width);
                assertThat(img.getHeight()).isEqualTo(f.height);
                Files.write(OUT.resolve(f.name().toLowerCase() + "-" + t.name().toLowerCase() + ".png"), png);
            }
        }
    }

    @Test
    void rendersWithoutPhotoOrPrice() throws Exception {
        byte[] png = PosterRenderer.product(new PosterRenderer.Tile("Notion Masterclass", null, null, null, null, null),
                "Elaatech", "elaatech.in", PosterRenderer.Format.SQUARE, PosterRenderer.Theme.DARK);
        assertThat(ImageIO.read(new ByteArrayInputStream(png)).getWidth()).isEqualTo(1080);
    }

    @Test
    void rendersCollectionBanners() throws Exception {
        Files.createDirectories(OUT);
        List<PosterRenderer.Tile> tiles = List.of(
                new PosterRenderer.Tile("Boat Airdopes 141", null, "₹1,099", null, 76, photo()),
                new PosterRenderer.Tile("Noise Buds VS104", null, "₹899", null, 70, photo()),
                new PosterRenderer.Tile("JBL Wave 200", null, "₹2,999", null, 40, photo()),
                new PosterRenderer.Tile("realme Buds T110", null, "₹1,299", null, null, photo()));
        for (PosterRenderer.Format f : List.of(PosterRenderer.Format.SQUARE, PosterRenderer.Format.HERO, PosterRenderer.Format.STORY)) {
            byte[] png = PosterRenderer.collection("Earbuds sale: up to 76% off", "Top picks this week", tiles, "Elaashop", "elaashop.in",
                    f, PosterRenderer.Theme.SALE);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            assertThat(img.getWidth()).isEqualTo(f.width);
            Files.write(OUT.resolve("collection-" + f.name().toLowerCase() + ".png"), png);
        }
    }
}
