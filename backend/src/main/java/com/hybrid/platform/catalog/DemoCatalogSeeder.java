package com.hybrid.platform.catalog;

import com.hybrid.platform.affiliate.AffiliateLink;
import com.hybrid.platform.affiliate.AffiliateMerchant;
import com.hybrid.platform.affiliate.AffiliateRepositories.LinkRepository;
import com.hybrid.platform.affiliate.AffiliateRepositories.MerchantRepository;
import com.hybrid.platform.catalog.CatalogEnums.DeliveryKind;
import com.hybrid.platform.catalog.CatalogEnums.ProductStatus;
import com.hybrid.platform.catalog.CatalogEnums.ProductType;
import com.hybrid.platform.catalog.CatalogEnums.SellingMethod;
import com.hybrid.platform.common.AppProperties;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.crypto.Hashing;
import com.hybrid.platform.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;

/**
 * Seeds clearly labelled demo affiliate offers so empty storefronts look filled during setup.
 * Idempotent by slug. Disabled in tests ({@code app.demo-catalog=false}).
 */
@Component
@ConditionalOnProperty(name = "app.demo-catalog", havingValue = "true", matchIfMissing = true)
public class DemoCatalogSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoCatalogSeeder.class);
    private static final String DISCLOSURE =
            "This is a demo affiliate listing. As an Amazon Associate / partner we may earn from qualifying purchases. "
                    + "The Buy button opens example.com for layout preview only.";

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final MerchantRepository merchants;
    private final LinkRepository links;
    private final FileStorage storage;
    private final ImageOptimizer images;
    private final AppProperties props;

    public DemoCatalogSeeder(ProductRepository products, CategoryRepository categories, MerchantRepository merchants,
                             LinkRepository links, FileStorage storage, ImageOptimizer images, AppProperties props) {
        this.products = products;
        this.categories = categories;
        this.merchants = merchants;
        this.links = links;
        this.storage = storage;
        this.images = images;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        archiveLegacyDirectDemos();
        int shop = seedShop();
        int digital = seedDigital();
        if (shop + digital > 0) {
            log.info("Seeded {} demo affiliate offer(s) ({} shop, {} digital). Titles start with [Demo] — delete before launch.",
                    shop + digital, shop, digital);
        }
    }

    /** Older builds seeded direct-sale demos; hide them when the site is affiliate-only. */
    private void archiveLegacyDirectDemos() {
        if (!props.affiliateOnly()) return;
        for (Storefront sf : Storefront.values()) {
            for (String slug : new String[]{
                    "demo-wireless-earbuds", "demo-desk-lamp", "demo-photo-editor-licence", "demo-excel-templates"}) {
                products.findByStorefrontAndSlug(sf, slug).ifPresent(p -> {
                    if (p.getSellingMethod() == SellingMethod.DIRECT && p.getStatus() != ProductStatus.ARCHIVED) {
                        p.setStatus(ProductStatus.ARCHIVED);
                        log.info("Archived legacy direct demo product {}/{}", sf.path(), slug);
                    }
                });
            }
        }
    }

    private int seedShop() {
        Storefront sf = Storefront.SHOP;
        Long audio = category(sf, "Audio", "audio", 0, "Demo category for headphones and speakers.");
        Long home = category(sf, "Home", "home", 1, "Demo category for lifestyle products.");
        int n = 0;
        n += affiliate(sf, ProductType.PHYSICAL, "demo-wireless-earbuds-deal", "[Demo] Wireless Earbuds",
                "DEMO AFFILIATE OFFER — opens example.com. Not a real purchase on this site.",
                "Curated partner deal for layout preview. Shoppers leave Elaashop to buy on the partner store.",
                "Partner Demo", audio, 249900L, 0xA6C93A);
        n += affiliate(sf, ProductType.PHYSICAL, "demo-desk-lamp-deal", "[Demo] LED Desk Lamp",
                "DEMO AFFILIATE OFFER — opens example.com. Not a real purchase on this site.",
                "Sample partner listing for the shop catalog. Delete when you add real affiliate links.",
                "Partner Demo", home, 129900L, 0x7FB2FF);
        n += affiliate(sf, ProductType.PHYSICAL, "demo-partner-speaker", "[Demo] Partner Bluetooth Speaker",
                "DEMO AFFILIATE OFFER — opens example.com. Not a real purchase on this site.",
                "Shows how a partner / affiliate product appears in Elaashop.",
                "Partner Demo", audio, 399900L, 0xCDB6FF);
        return n;
    }

    private int seedDigital() {
        Storefront sf = Storefront.DIGITAL;
        Long software = category(sf, "Software", "software", 0, "Demo category for software partner offers.");
        Long courses = category(sf, "Courses", "courses", 1, "Demo category for course partner offers.");
        int n = 0;
        n += affiliate(sf, ProductType.DIGITAL, "demo-photo-editor-deal", "[Demo] Photo Editor Pro",
                "DEMO AFFILIATE OFFER — opens example.com. Licence is sold by the partner, not here.",
                "Sample digital affiliate listing for Elaatech. Buy happens on the partner site.",
                "Partner Demo", software, 199900L, 0xA6C93A, "software");
        n += affiliate(sf, ProductType.DIGITAL, "demo-excel-templates-deal", "[Demo] Excel Templates Pack",
                "DEMO AFFILIATE OFFER — opens example.com. Files are fulfilled by the partner.",
                "Sample templates offer for the digital storefront layout.",
                "Partner Demo", software, 99900L, 0x7FB2FF, "templates");
        n += affiliate(sf, ProductType.DIGITAL, "demo-partner-course", "[Demo] Partner Online Course",
                "DEMO AFFILIATE OFFER — opens example.com.",
                "Shows how an affiliate digital course looks on Elaatech.",
                "Partner Demo", courses, 149900L, 0xCDB6FF, "course");
        return n;
    }

    private Long category(Storefront sf, String name, String slug, int order, String description) {
        return categories.findByStorefrontAndSlug(sf, slug).map(Category::getId).orElseGet(() -> {
            Category c = new Category(sf, name, slug, order);
            c.setDescription(description);
            return categories.save(c).getId();
        });
    }

    private int affiliate(Storefront sf, ProductType type, String slug, String title, String summary, String description,
                          String brand, Long categoryId, long displayPrice, int color) {
        return affiliate(sf, type, slug, title, summary, description, brand, categoryId, displayPrice, color, null);
    }

    private int affiliate(Storefront sf, ProductType type, String slug, String title, String summary, String description,
                          String brand, Long categoryId, long displayPrice, int color, String digitalFormat) {
        if (products.existsByStorefrontAndSlug(sf, slug)) return 0;
        Product p = new Product(sf, type, SellingMethod.AFFILIATE, title, slug);
        p.setSummary(summary);
        p.setDescription(description);
        p.setBrand(brand);
        p.setCategoryId(categoryId);
        p.setFocusKeyword("demo affiliate");
        p.setMetaTitle(title);
        p.setMetaDescription(summary);
        p.setStatus(ProductStatus.PUBLISHED);
        p.setPublishedAt(Instant.now());
        p.setNoindex(true);
        if (type == ProductType.DIGITAL) {
            p.setDeliveryKind(DeliveryKind.LICENSE_KEY);
            p.setDigitalFormat(digitalFormat);
        }
        p.addVariant(new ProductVariant("Default", true, 0));
        attachImage(p, title, color);
        p = products.save(p);
        attachAffiliate(sf, p.getId(), "https://example.com/demo/" + slug, displayPrice);
        return 1;
    }

    private void attachAffiliate(Storefront sf, Long productId, String url, long displayPrice) {
        AffiliateMerchant merchant = merchants.findByStorefrontAndDomain(sf, "example.com")
                .orElseGet(() -> merchants.save(new AffiliateMerchant(sf, "Demo Merchant (example.com)", "example.com")));
        AffiliateLink link = new AffiliateLink(sf, productId, "d" + Hashing.randomCode(7));
        link.setMerchant(merchant);
        link.setDestinationUrl(url);
        link.setDisplayPriceMinor(displayPrice);
        link.setPriceCheckedAt(Instant.now());
        link.setDisclosure(DISCLOSURE);
        links.save(link);
    }

    private void attachImage(Product p, String title, int color) {
        try {
            byte[] png = placeholderPng(title, color);
            FileStorage.StoredFile stored = storage.store(FileStorage.Area.PUBLIC, new ByteArrayInputStream(png), "png",
                    png.length + 1L);
            ProductImage image = new ProductImage(stored.key(), title + " — demo placeholder", 0);
            ImageOptimizer.Result r = images.renditions(stored.key());
            image.setRenditions(r.thumbKey(), r.largeKey(), r.width(), r.height());
            p.addImage(image);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create demo product image for " + p.getSlug(), e);
        }
    }

    static byte[] placeholderPng(String title, int rgb) throws IOException {
        int size = 640;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(18, 20, 22));
            g.fillRect(0, 0, size, size);
            g.setColor(new Color(rgb));
            g.fillRoundRect(40, 40, size - 80, size - 80, 48, 48);
            g.setColor(new Color(18, 20, 22));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 64));
            String demo = "DEMO";
            FontMetrics fm = g.getFontMetrics();
            g.drawString(demo, (size - fm.stringWidth(demo)) / 2, size / 2 - 20);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
            fm = g.getFontMetrics();
            String deal = "AFFILIATE";
            g.drawString(deal, (size - fm.stringWidth(deal)) / 2, size / 2 + 18);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 26));
            fm = g.getFontMetrics();
            String label = title.replace("[Demo] ", "");
            if (fm.stringWidth(label) > size - 100) {
                while (label.length() > 8 && fm.stringWidth(label + "…") > size - 100) {
                    label = label.substring(0, label.length() - 1);
                }
                label = label + "…";
            }
            g.drawString(label, (size - fm.stringWidth(label)) / 2, size / 2 + 58);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
