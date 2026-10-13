package com.hybrid.platform.importer;

import com.hybrid.platform.affiliate.AffiliateMerchant;
import com.hybrid.platform.affiliate.AffiliateRepositories;
import com.hybrid.platform.affiliate.AffiliateService;
import com.hybrid.platform.affiliate.AffiliateUrlPolicy;
import com.hybrid.platform.catalog.CatalogAdminService;
import com.hybrid.platform.catalog.CatalogEnums.DeliveryKind;
import com.hybrid.platform.catalog.CatalogEnums.ProductType;
import com.hybrid.platform.catalog.CatalogEnums.SellingMethod;
import com.hybrid.platform.catalog.Category;
import com.hybrid.platform.catalog.CategoryRepository;
import com.hybrid.platform.catalog.ProductRepository;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.audit.AuditService;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.security.AdminPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.*;

/**
 * Bulk import of affiliate products from a spreadsheet. Each row becomes a draft product with its partner link;
 * the admin adds a photo and publishes. A dry run shows what will happen first. Rows are saved one by one, so one
 * bad row never blocks the others.
 */
@Service
public class ProductImportService {

    static final int MAX_ROWS = 500;
    static final int MAX_CHARS = 2_000_000;

    /** Accepted column names (lower case, spaces) for each field. */
    private static final Map<String, List<String>> COLUMNS = Map.of(
            "title", List.of("title", "name", "product", "product name", "product title"),
            "link", List.of("link", "url", "affiliate link", "affiliate url", "buy link", "deal link"),
            "price", List.of("price", "deal price", "sale price", "offer price"),
            "mrp", List.of("mrp", "list price", "original price", "regular price"),
            "category", List.of("category", "category name"),
            "brand", List.of("brand"),
            "summary", List.of("summary", "short description", "description"),
            "highlights", List.of("highlights", "features", "key features"),
            "store", List.of("store", "store name", "merchant", "shop"));

    public record ImportRequest(String csv, Boolean dryRun, Boolean approveNewStores) {
    }

    public record ImportRow(int row, String title, String domain, String store, String category, Long priceMinor,
                            Long mrpMinor, String status, String message, Long productId) {
    }

    public record ImportReport(boolean dryRun, int total, int ready, int failed, int created, List<String> newStores,
                               List<ImportRow> rows) {
    }

    private record Parsed(int row, String title, String link, URI uri, String domain, String storeName, Category category,
                          Long priceMinor, Long mrpMinor, String brand, String summary, String highlights,
                          boolean newStore, String error) {
    }

    private final CatalogAdminService catalog;
    private final AffiliateService affiliate;
    private final AffiliateRepositories.MerchantRepository merchants;
    private final CategoryRepository categories;
    private final ProductRepository products;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public ProductImportService(CatalogAdminService catalog, AffiliateService affiliate, AffiliateRepositories.MerchantRepository merchants,
                                CategoryRepository categories, ProductRepository products, AuditService audit,
                                PlatformTransactionManager transactions) {
        this.catalog = catalog;
        this.affiliate = affiliate;
        this.merchants = merchants;
        this.categories = categories;
        this.products = products;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactions);
    }

    public ImportReport run(AdminPrincipal admin, ImportRequest in) {
        boolean dryRun = !Boolean.FALSE.equals(in.dryRun());
        boolean approve = Boolean.TRUE.equals(in.approveNewStores());
        if (in.csv() == null || in.csv().isBlank()) throw ApiException.badRequest("IMPORT_EMPTY", "Choose a CSV file with a header row and at least one product");
        if (in.csv().length() > MAX_CHARS) throw ApiException.rule("IMPORT_TOO_LARGE", "The file is too large. Import at most " + MAX_ROWS + " products at a time");
        List<List<String>> table = CsvReader.parse(in.csv());
        if (table.size() < 2) throw ApiException.badRequest("IMPORT_EMPTY", "The file needs a header row and at least one product");
        if (table.size() - 1 > MAX_ROWS) throw ApiException.rule("IMPORT_TOO_LARGE", "Import at most " + MAX_ROWS + " products at a time");
        Map<String, Integer> index = header(table.get(0));
        if (!index.containsKey("title") || !index.containsKey("link")) {
            throw ApiException.badRequest("IMPORT_COLUMNS_MISSING", "The first row must name the columns, with at least “title” and “link”. Download the template to see the format.");
        }

        List<Parsed> parsed = tx.execute(status -> {
            List<Category> cats = categories.findByStorefrontAndActiveTrueOrderBySortOrderAscNameAsc(admin.storefront());
            Set<String> seen = new HashSet<>();
            List<Parsed> out = new ArrayList<>();
            for (int i = 1; i < table.size(); i++) out.add(parse(admin.storefront(), i + 1, table.get(i), index, cats, seen));
            return out;
        });

        Set<String> newStores = new TreeSet<>();
        parsed.stream().filter(p -> p.error() == null && p.newStore()).forEach(p -> newStores.add(p.domain()));
        List<ImportRow> rows = new ArrayList<>();
        int created = 0;
        for (Parsed p : parsed) {
            String category = p.category() == null ? null : p.category().getName();
            if (p.error() != null) {
                rows.add(row(p, category, "ERROR", p.error(), null));
            } else if (p.newStore() && !approve) {
                rows.add(row(p, category, "NEW_STORE", dryRun ? "New store " + p.domain() + " — approve it to import this row"
                        : "Skipped: approve the new store " + p.domain() + " first", null));
            } else if (dryRun) {
                rows.add(row(p, category, "READY", "Will be added as a draft", null));
            } else {
                try {
                    Long id = tx.execute(status -> create(admin, p, approve));
                    created++;
                    rows.add(row(p, category, "CREATED", "Added as a draft — add a photo, then publish", id));
                } catch (ApiException e) {
                    rows.add(row(p, category, "ERROR", e.getMessage(), null));
                }
            }
        }
        if (!dryRun && created > 0) {
            int count = created;
            tx.executeWithoutResult(status -> audit.record(admin, "PRODUCTS_IMPORTED", "Product", null, count + " products from CSV"));
        }
        int ready = (int) rows.stream().filter(r -> r.status().equals("READY") || r.status().equals("CREATED")
                || (approve && r.status().equals("NEW_STORE"))).count();
        int failed = (int) rows.stream().filter(r -> r.status().equals("ERROR")).count();
        return new ImportReport(dryRun, rows.size(), ready, failed, created, List.copyOf(newStores), rows);
    }

    private Long create(AdminPrincipal admin, Parsed p, boolean approve) {
        boolean digital = admin.storefront() == Storefront.DIGITAL;
        CatalogAdminService.AdminProductView product = catalog.create(admin, new CatalogAdminService.ProductInput(
                digital ? ProductType.DIGITAL : ProductType.PHYSICAL, SellingMethod.AFFILIATE, p.title(), null,
                p.summary(), null, p.brand(), p.category() == null ? null : p.category().getId(),
                null, digital ? DeliveryKind.DOWNLOAD : null, null, null, null, null,
                null, null, null, null, null, null, p.highlights(), null, null, null, null, null));
        affiliate.setLink(admin, product.id(), new AffiliateService.SetLinkCommand(p.link(), p.priceMinor(), p.mrpMinor(), null,
                p.storeName(), approve));
        return product.id();
    }

    private Parsed parse(Storefront sf, int rowNo, List<String> cells, Map<String, Integer> index, List<Category> cats, Set<String> seen) {
        String title = cell(cells, index, "title");
        String link = cell(cells, index, "link");
        String storeName = cell(cells, index, "store");
        String brand = cell(cells, index, "brand");
        String summary = cell(cells, index, "summary");
        String highlightsRaw = cell(cells, index, "highlights");
        String highlights = highlightsRaw == null ? null : String.join("\n", Arrays.stream(highlightsRaw.split("[|;\\n]"))
                .map(String::strip).filter(s -> !s.isEmpty()).toList());
        String error = null;
        URI uri = null;
        String domain = null;
        boolean newStore = false;
        Category category = null;
        Long price = null;
        Long mrp = null;
        try {
            if (title == null) throw ApiException.badRequest("IMPORT_TITLE", "Title is missing");
            if (title.length() > 200) throw ApiException.badRequest("IMPORT_TITLE", "Title is longer than 200 characters");
            if (!seen.add(title.toLowerCase(Locale.ROOT))) throw ApiException.badRequest("IMPORT_DUPLICATE", "This title appears twice in the file");
            if (products.existsByStorefrontAndTitleIgnoreCase(sf, title)) {
                throw ApiException.badRequest("IMPORT_DUPLICATE", "Already in your catalog (same title)");
            }
            if (link == null) throw ApiException.badRequest("IMPORT_LINK", "Partner link is missing");
            uri = AffiliateUrlPolicy.parse(link);
            domain = AffiliateUrlPolicy.domainKey(uri);
            AffiliateMerchant merchant = merchants.findByStorefrontAndDomain(sf, domain).orElse(null);
            if (merchant == null) {
                newStore = true;
                AffiliateUrlPolicy.requireAllowedScheme(uri, "http".equalsIgnoreCase(uri.getScheme()));
            } else {
                if (!merchant.isActive()) throw ApiException.rule("IMPORT_STORE_DISABLED", "The store " + merchant.getName() + " is turned off");
                AffiliateUrlPolicy.requireAllowedScheme(uri, merchant.isAllowHttp());
                if (storeName == null) storeName = merchant.getName();
            }
            String categoryName = cell(cells, index, "category");
            if (categoryName != null) {
                category = cats.stream().filter(c -> c.getName().equalsIgnoreCase(categoryName) || c.getSlug().equalsIgnoreCase(categoryName))
                        .findFirst().orElseThrow(() -> ApiException.badRequest("IMPORT_CATEGORY", "No category called “" + categoryName
                                + "”. Use one of: " + String.join(", ", cats.stream().map(Category::getName).toList())));
            }
            price = rupees(cell(cells, index, "price"), "Price");
            mrp = rupees(cell(cells, index, "mrp"), "MRP");
            if (price != null && mrp != null && mrp <= price) throw ApiException.badRequest("IMPORT_MRP", "MRP must be higher than the price");
        } catch (ApiException e) {
            error = e.getMessage();
        }
        return new Parsed(rowNo, title, link, uri, domain, storeName, category, price, mrp, brand,
                summary == null ? null : summary.length() > 300 ? summary.substring(0, 300) : summary, highlights, newStore, error);
    }

    private static ImportRow row(Parsed p, String category, String status, String message, Long id) {
        return new ImportRow(p.row(), p.title(), p.domain(), p.storeName(), category, p.priceMinor(), p.mrpMinor(), status, message, id);
    }

    private static Map<String, Integer> header(List<String> cells) {
        Map<String, Integer> out = new HashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            String h = cells.get(i).toLowerCase(Locale.ROOT).replaceAll("[_\\-]+", " ").replaceAll("\\(.*?\\)", "").replaceAll("\\s+", " ").strip();
            for (var e : COLUMNS.entrySet()) if (e.getValue().contains(h)) out.putIfAbsent(e.getKey(), i);
        }
        return out;
    }

    private static String cell(List<String> cells, Map<String, Integer> index, String field) {
        Integer i = index.get(field);
        if (i == null || i >= cells.size()) return null;
        String v = cells.get(i).strip();
        return v.isEmpty() ? null : v;
    }

    /** "₹1,499", "1499.00", "Rs. 1,499" → 149900 paise. */
    static Long rupees(String raw, String label) {
        if (raw == null) return null;
        String v = raw.replaceAll("(?i)rs\\.?|inr|₹|,|\\s", "");
        if (v.isEmpty()) return null;
        try {
            BigDecimal d = new BigDecimal(v).setScale(2, RoundingMode.HALF_UP);
            if (d.signum() <= 0 || d.compareTo(BigDecimal.valueOf(10_000_000)) > 0) throw new NumberFormatException();
            return d.movePointRight(2).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw ApiException.badRequest("IMPORT_PRICE", label + " “" + raw + "” is not a valid amount in rupees");
        }
    }
}
