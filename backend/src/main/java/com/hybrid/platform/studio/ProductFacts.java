package com.hybrid.platform.studio;

import com.hybrid.platform.catalog.CatalogPublicService;
import com.hybrid.platform.catalog.CatalogPublicService.ProductDetail;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything the content templates may say about a product, taken from its live public page. Templates never invent
 * facts: a section is left out when the product has no data for it. {@code priceMinor} is null when the store hides
 * prices or the partner price is unknown.
 */
public record ProductFacts(Long id, String slug, String title, String brand, String category, String categorySlug,
                           String summary, List<String> highlights, List<String> pros, List<String> cons, String verdict,
                           Integer expertRating, List<CatalogPublicService.Spec> specs, Long priceMinor, Long mrpMinor,
                           Integer discountPercent, String merchantName, boolean affiliate, boolean digital,
                           String deliveryKind, boolean lowest30d, Integer dropPercent, String productUrl, String imageUrl,
                           String imageKey) {

    public static ProductFacts of(ProductDetail d, String productUrl, String absoluteImageUrl, String imageKey) {
        Long price = null;
        Long mrp = null;
        Integer discount = null;
        String merchant = null;
        boolean affiliate = "AFFILIATE".equals(d.sellingMethod());
        if (affiliate && d.affiliate() != null) {
            merchant = d.affiliate().merchantName();
            if (!d.affiliate().priceHidden()) {
                price = d.affiliate().displayPriceMinor();
                mrp = d.affiliate().listPriceMinor();
                discount = d.affiliate().discountPercent();
            }
        } else if (!affiliate && d.variants() != null) {
            var v = d.variants().stream().filter(CatalogPublicService.VariantView::available).findFirst()
                    .orElse(d.variants().isEmpty() ? null : d.variants().get(0));
            if (v != null) {
                price = v.priceMinor();
                mrp = v.compareAtMinor();
            }
        }
        if (price != null && mrp != null && mrp > price && discount == null) {
            discount = (int) ((mrp - price) * 100 / mrp);
        }
        if (mrp != null && price != null && mrp <= price) mrp = null;
        var stats = d.priceStats();
        return new ProductFacts(d.id(), d.slug(), d.title(), blankToNull(d.brand()),
                d.category() == null ? null : d.category().name(), d.category() == null ? null : d.category().slug(),
                blankToNull(d.summary()), clean(d.highlights()), clean(d.pros()), clean(d.cons()), blankToNull(d.verdict()),
                d.expertRating(), d.specs() == null ? List.of() : d.specs(), price, mrp, discount, merchant, affiliate,
                "DIGITAL".equals(d.productType()), d.deliveryKind(),
                price != null && stats != null && stats.lowest30d(), price == null || stats == null ? null : stats.dropPercent(),
                productUrl, absoluteImageUrl, imageKey);
    }

    public boolean hasPrice() {
        return priceMinor != null && priceMinor > 0;
    }

    /** "the Acme X2" / "Acme X2": the brand is only added when the title does not already start with it. */
    public String displayName() {
        if (brand == null || title.toLowerCase(Locale.ROOT).startsWith(brand.toLowerCase(Locale.ROOT))) return title;
        return brand + " " + title;
    }

    private static List<String> clean(List<String> lines) {
        List<String> out = new ArrayList<>();
        if (lines == null) return out;
        for (String l : lines) {
            if (l == null) continue;
            String t = l.strip().replaceAll("\\s+", " ");
            while (t.endsWith(".") || t.endsWith(";")) t = t.substring(0, t.length() - 1).strip();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }
}
