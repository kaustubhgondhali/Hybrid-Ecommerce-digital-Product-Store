package com.hybrid.platform.commerce.settings;

import com.hybrid.platform.common.AppProperties;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.audit.AuditService;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.commerce.settings.SettingsRepositories.ShippingMethodRepository;
import com.hybrid.platform.commerce.settings.SettingsRepositories.StoreSettingsRepository;
import com.hybrid.platform.security.AdminPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class SettingsService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PREFIX = Pattern.compile("^[A-Z0-9]{1,10}$");

    private static final Pattern STATE_CODE = Pattern.compile("^\\d{2}$");
    private static final Pattern HSN = Pattern.compile("^\\d{4,8}$");
    private static final Pattern GSTIN = Pattern.compile("^\\d{2}[A-Z]{5}\\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");
    private static final Pattern PIN = Pattern.compile("^\\d{6}$");
    private static final Pattern GA4_ID = Pattern.compile("^G-[A-Z0-9]{4,16}$");
    private static final Pattern PIXEL_ID = Pattern.compile("^\\d{10,20}$");

    public record StoreSettingsView(String storefront, String storeName, String supportEmail, int taxRateBps,
                                    String sellerLegalName, String sellerAddress, String sellerTaxId,
                                    String invoicePrefix, String sellerStateCode, boolean pricesIncludeTax,
                                    String defaultHsnSac, boolean reviewsAutoApprove, boolean codEnabled,
                                    Long codMaxMinor, long codFeeMinor, String pincodeMode, String pincodeList,
                                    String codPincodeList, String deliveryEstimate, boolean affiliateOnly,
                                    Instant updatedAt, String whatsappNumber, Long dealOfDayProductId, Instant dealOfDayEndsAt,
                                    String ga4Id, String metaPixelId) {
    }

    public record StoreSettingsInput(String storeName, String supportEmail, Integer taxRateBps, String sellerLegalName,
                                     String sellerAddress, String sellerTaxId, String invoicePrefix,
                                     String sellerStateCode, Boolean pricesIncludeTax, String defaultHsnSac,
                                     Boolean reviewsAutoApprove, Boolean codEnabled, Long codMaxMinor, Long codFeeMinor,
                                     String pincodeMode, String pincodeList, String codPincodeList,
                                     String deliveryEstimate, String whatsappNumber, String ga4Id, String metaPixelId,
                                     Boolean onsiteCheckout) {
    }

    public record ShippingMethodView(Long id, String name, long rateMinor, Long freeAboveMinor, boolean active, int sortOrder) {
    }

    public record ShippingMethodInput(String name, Long rateMinor, Long freeAboveMinor, Boolean active, Integer sortOrder) {
    }

    public record PublicStoreInfo(String storefront, String storeName, String supportEmail, String currency,
                                  int taxRateBps, boolean pricesIncludeTax, boolean codEnabled, boolean affiliateOnly,
                                  List<ShippingMethodView> shippingMethods, String whatsappNumber, String ga4Id, String metaPixelId) {
    }

    private final StoreSettingsRepository settings;
    private final ShippingMethodRepository shipping;
    private final AuditService audit;
    private final AppProperties props;

    public SettingsService(StoreSettingsRepository settings, ShippingMethodRepository shipping, AuditService audit,
                           AppProperties props) {
        this.settings = settings;
        this.shipping = shipping;
        this.audit = audit;
        this.props = props;
    }

    @Transactional
    public StoreSettings entity(Storefront sf) {
        return settings.findById(sf).orElseGet(() -> settings.save(
                new StoreSettings(sf, sf == Storefront.SHOP ? "Elaashop" : "Elaatech", sf == Storefront.SHOP ? "SHP" : "DIG")));
    }

    @Transactional
    public StoreSettingsView get(Storefront sf) {
        return view(entity(sf));
    }

    @Transactional
    public PublicStoreInfo publicInfo(Storefront sf, String currency) {
        StoreSettings s = entity(sf);
        boolean affiliateOnly = affiliateOnly(s);
        return new PublicStoreInfo(sf.path(), s.getStoreName(), s.getSupportEmail(), currency, s.getTaxRateBps(),
                s.isPricesIncludeTax(), !affiliateOnly && s.isCodEnabled() && sf == Storefront.SHOP,
                affiliateOnly, activeShippingMethods(sf), s.getWhatsappNumber(), s.getGa4Id(), s.getMetaPixelId());
    }

    /**
     * True when this storefront only lists partner (affiliate) deals: no cart checkout, own-stock or dropship products.
     * Admins switch it per storefront; until they do, the AFFILIATE_ONLY environment default applies.
     */
    @Transactional(readOnly = true)
    public boolean affiliateOnly(Storefront sf) {
        return settings.findById(sf).map(this::affiliateOnly).orElse(props.affiliateOnly());
    }

    private boolean affiliateOnly(StoreSettings s) {
        return s.getOnsiteCheckout() == null ? props.affiliateOnly() : !s.getOnsiteCheckout();
    }

    /**
     * WhatsApp chat number: digits with country code. A 10-digit Indian mobile number gets 91 in front; blank removes
     * the chat button.
     */
    /** Analytics IDs: trimmed and upper-cased; blank removes the tracker. */
    static String trackingId(String raw, Pattern format, String code, String message) {
        String v = raw.strip().toUpperCase(java.util.Locale.ROOT);
        if (v.isEmpty()) return null;
        if (!format.matcher(v).matches()) throw ApiException.badRequest(code, message);
        return v;
    }

    static String whatsapp(String raw) {
        String digits = raw.replaceAll("[\\s()+\\-]", "");
        if (digits.isEmpty()) return null;
        if (!digits.matches("\\d{10,15}")) {
            throw ApiException.badRequest("INVALID_WHATSAPP", "Enter the WhatsApp number with country code, e.g. +91 98765 43210");
        }
        return digits.length() == 10 ? "91" + digits : digits;
    }

    /** Sets (or with a null product, clears) the Deal of the Day shown with a countdown on the home page. */
    @Transactional
    public StoreSettingsView setDealOfDay(AdminPrincipal admin, Long productId, Instant endsAt) {
        StoreSettings s = entity(admin.storefront());
        if (productId == null) {
            s.setDealOfDay(null, null);
        } else {
            if (endsAt == null || !endsAt.isAfter(Instant.now())) {
                throw ApiException.badRequest("DEAL_END_REQUIRED", "Choose when the deal ends (in the future)");
            }
            if (endsAt.isAfter(Instant.now().plus(java.time.Duration.ofDays(31)))) {
                throw ApiException.badRequest("DEAL_TOO_LONG", "A Deal of the Day can run for up to 31 days");
            }
            s.setDealOfDay(productId, endsAt);
        }
        audit.record(admin, "DEAL_OF_DAY_SET", "StoreSettings", null, productId == null ? "cleared" : productId + " until " + endsAt);
        return view(s);
    }

    @Transactional
    public StoreSettingsView update(AdminPrincipal admin, StoreSettingsInput in) {
        StoreSettings s = entity(admin.storefront());
        if (in.storeName() != null) {
            String name = in.storeName().trim();
            if (name.isEmpty() || name.length() > 120) {
                throw ApiException.badRequest("INVALID_STORE_NAME", "Store name must be 1-120 characters");
            }
            s.setStoreName(name);
        }
        if (in.supportEmail() != null) {
            String email = in.supportEmail().trim();
            if (!email.isEmpty() && (!EMAIL.matcher(email).matches() || email.length() > 254)) {
                throw ApiException.badRequest("INVALID_EMAIL", "Support email is not valid");
            }
            s.setSupportEmail(email.isEmpty() ? null : email);
        }
        if (in.taxRateBps() != null) {
            if (in.taxRateBps() < 0 || in.taxRateBps() > 5000) {
                throw ApiException.badRequest("INVALID_TAX_RATE", "Tax rate must be between 0% and 50%");
            }
            s.setTaxRateBps(in.taxRateBps());
        }
        if (in.sellerLegalName() != null) s.setSellerLegalName(limit(in.sellerLegalName(), 200));
        if (in.sellerAddress() != null) s.setSellerAddress(limit(in.sellerAddress(), 500));
        if (in.sellerStateCode() != null) {
            String code = in.sellerStateCode().trim();
            if (!code.isEmpty() && !STATE_CODE.matcher(code).matches()) {
                throw ApiException.badRequest("INVALID_STATE_CODE", "State code must be the 2-digit GST state code");
            }
            s.setSellerStateCode(code.isEmpty() ? null : code);
        }
        if (in.sellerTaxId() != null) {
            String gstin = limit(in.sellerTaxId().toUpperCase(java.util.Locale.ROOT), 40);
            if (gstin != null && !GSTIN.matcher(gstin).matches()) {
                throw ApiException.badRequest("INVALID_GSTIN", "GSTIN must be 15 characters, e.g. 27ABCDE1234F1Z5");
            }
            s.setSellerTaxId(gstin);
            if (gstin != null && s.getSellerStateCode() == null) s.setSellerStateCode(gstin.substring(0, 2));
        }
        if (in.onsiteCheckout() != null) s.setOnsiteCheckout(in.onsiteCheckout());
        if (in.pricesIncludeTax() != null) s.setPricesIncludeTax(in.pricesIncludeTax());
        if (in.defaultHsnSac() != null) s.setDefaultHsnSac(hsn(in.defaultHsnSac()));
        if (in.reviewsAutoApprove() != null) s.setReviewsAutoApprove(in.reviewsAutoApprove());
        if (in.codEnabled() != null) {
            if (in.codEnabled() && admin.storefront() != Storefront.SHOP) {
                throw ApiException.rule("COD_NOT_SUPPORTED", "Cash on delivery is only available for physical products");
            }
            s.setCodEnabled(in.codEnabled());
        }
        if (in.codMaxMinor() != null) s.setCodMaxMinor(in.codMaxMinor() <= 0 ? null : in.codMaxMinor());
        if (in.codFeeMinor() != null) {
            if (in.codFeeMinor() < 0 || in.codFeeMinor() > 1_000_000) {
                throw ApiException.badRequest("INVALID_COD_FEE", "COD fee is out of range");
            }
            s.setCodFeeMinor(in.codFeeMinor());
        }
        if (in.pincodeMode() != null) {
            String mode = in.pincodeMode().trim().toUpperCase(java.util.Locale.ROOT);
            if (!List.of("ALL", "ALLOW", "BLOCK").contains(mode)) {
                throw ApiException.badRequest("INVALID_PINCODE_MODE", "PIN code mode must be ALL, ALLOW or BLOCK");
            }
            s.setPincodeMode(mode);
        }
        if (in.pincodeList() != null) s.setPincodeList(pinList(in.pincodeList()));
        if (in.codPincodeList() != null) s.setCodPincodeList(pinList(in.codPincodeList()));
        if (in.deliveryEstimate() != null) s.setDeliveryEstimate(limit(in.deliveryEstimate(), 80));
        if (in.whatsappNumber() != null) s.setWhatsappNumber(whatsapp(in.whatsappNumber()));
        if (in.ga4Id() != null) s.setGa4Id(trackingId(in.ga4Id(), GA4_ID, "INVALID_GA4_ID",
                "Google Analytics ID looks like G-XXXXXXXXXX (Admin \u2192 Data streams in Google Analytics)"));
        if (in.metaPixelId() != null) s.setMetaPixelId(trackingId(in.metaPixelId(), PIXEL_ID, "INVALID_PIXEL_ID",
                "Meta Pixel ID is a number of 10 to 20 digits (Events Manager \u2192 Data sources)"));
        if (in.invoicePrefix() != null) {
            String prefix = in.invoicePrefix().trim().toUpperCase();
            if (!PREFIX.matcher(prefix).matches()) {
                throw ApiException.badRequest("INVALID_INVOICE_PREFIX", "Invoice prefix must be 1-10 letters or digits");
            }
            s.setInvoicePrefix(prefix);
        }
        settings.save(s);
        audit.record(admin, "STORE_SETTINGS_UPDATED", "StoreSettings", admin.storefront().name(), null);
        return view(s);
    }

    @Transactional(readOnly = true)
    public List<ShippingMethodView> listShippingMethods(Storefront sf) {
        return shipping.findByStorefrontOrderBySortOrderAscIdAsc(sf).stream().map(SettingsService::view).toList();
    }

    @Transactional(readOnly = true)
    public List<ShippingMethodView> activeShippingMethods(Storefront sf) {
        return shipping.findByStorefrontAndActiveTrueOrderBySortOrderAscIdAsc(sf).stream().map(SettingsService::view).toList();
    }

    @Transactional(readOnly = true)
    public ShippingMethod requireActiveShippingMethod(Storefront sf, Long id) {
        return shipping.findByIdAndStorefront(id, sf).filter(ShippingMethod::isActive)
                .orElseThrow(() -> ApiException.rule("SHIPPING_METHOD_INVALID", "Choose an available shipping method"));
    }

    @Transactional
    public ShippingMethodView createShippingMethod(AdminPrincipal admin, ShippingMethodInput in) {
        if (in.name() == null || in.rateMinor() == null) {
            throw ApiException.badRequest("INVALID_SHIPPING_METHOD", "Name and rate are required");
        }
        ShippingMethod m = new ShippingMethod(admin.storefront(), "x", 0, null);
        apply(m, in);
        shipping.save(m);
        audit.record(admin, "SHIPPING_METHOD_CREATED", "ShippingMethod", m.getId(), m.getName());
        return view(m);
    }

    @Transactional
    public ShippingMethodView updateShippingMethod(AdminPrincipal admin, Long id, ShippingMethodInput in) {
        ShippingMethod m = shipping.findByIdAndStorefront(id, admin.storefront())
                .orElseThrow(() -> ApiException.notFound("SHIPPING_METHOD_NOT_FOUND", "Shipping method not found"));
        apply(m, in);
        audit.record(admin, "SHIPPING_METHOD_UPDATED", "ShippingMethod", m.getId(), m.getName());
        return view(m);
    }

    private static void apply(ShippingMethod m, ShippingMethodInput in) {
        if (in.name() != null) {
            String name = in.name().trim();
            if (name.isEmpty() || name.length() > 120) {
                throw ApiException.badRequest("INVALID_SHIPPING_METHOD", "Name must be 1-120 characters");
            }
            m.setName(name);
        }
        if (in.rateMinor() != null) {
            if (in.rateMinor() < 0 || in.rateMinor() > 10_000_000) {
                throw ApiException.badRequest("INVALID_SHIPPING_RATE", "Shipping rate is out of range");
            }
            m.setRateMinor(in.rateMinor());
        }
        if (in.freeAboveMinor() != null) {
            m.setFreeAboveMinor(in.freeAboveMinor() <= 0 ? null : in.freeAboveMinor());
        }
        if (in.active() != null) m.setActive(in.active());
        if (in.sortOrder() != null) m.setSortOrder(in.sortOrder());
    }

    private static String limit(String value, int max) {
        String v = value.trim();
        if (v.length() > max) {
            throw ApiException.badRequest("VALUE_TOO_LONG", "Value must be at most " + max + " characters");
        }
        return v.isEmpty() ? null : v;
    }

    /** Validates an HSN (goods) or SAC (services) code; blank clears it. */
    public static String hsn(String value) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) return null;
        if (!HSN.matcher(v).matches()) {
            throw ApiException.badRequest("INVALID_HSN_SAC", "HSN/SAC code must be 4 to 8 digits");
        }
        return v;
    }

    /** Normalises a list of 6-digit PIN codes or prefixes (e.g. "110" covers 110001-110099) separated by commas/whitespace. */
    static String pinList(String raw) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String token : raw.split("[\\s,;]+")) {
            if (token.isEmpty()) continue;
            if (!token.matches("^\\d{2,6}$")) {
                throw ApiException.badRequest("INVALID_PINCODE", "\"" + limitText(token) + "\" is not a PIN code or prefix (2-6 digits)");
            }
            out.add(token);
        }
        if (out.size() > 20_000) throw ApiException.badRequest("TOO_MANY_PINCODES", "At most 20,000 PIN codes");
        return out.isEmpty() ? null : String.join("\n", out);
    }

    private static String limitText(String t) {
        return t.length() > 12 ? t.substring(0, 12) + "\u2026" : t;
    }

    public static boolean isPin(String pin) {
        return pin != null && PIN.matcher(pin).matches();
    }

    public static boolean isGstin(String gstin) {
        return gstin != null && GSTIN.matcher(gstin).matches();
    }

    private StoreSettingsView view(StoreSettings s) {
        return new StoreSettingsView(s.getStorefront().path(), s.getStoreName(), s.getSupportEmail(), s.getTaxRateBps(),
                s.getSellerLegalName(), s.getSellerAddress(), s.getSellerTaxId(), s.getInvoicePrefix(),
                s.getSellerStateCode(), s.isPricesIncludeTax(), s.getDefaultHsnSac(), s.isReviewsAutoApprove(),
                s.isCodEnabled(), s.getCodMaxMinor(), s.getCodFeeMinor(), s.getPincodeMode(), s.getPincodeList(),
                s.getCodPincodeList(), s.getDeliveryEstimate(), affiliateOnly(s), s.getUpdatedAt(),
                s.getWhatsappNumber(), s.getDealOfDayProductId(), s.getDealOfDayEndsAt(), s.getGa4Id(), s.getMetaPixelId());
    }

    private static ShippingMethodView view(ShippingMethod m) {
        return new ShippingMethodView(m.getId(), m.getName(), m.getRateMinor(), m.getFreeAboveMinor(), m.isActive(), m.getSortOrder());
    }
}
