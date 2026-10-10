package com.hybrid.platform.commerce.order;

import com.hybrid.platform.catalog.CatalogEnums.ProductType;
import com.hybrid.platform.catalog.CatalogEnums.SellingMethod;
import com.hybrid.platform.catalog.CatalogQueryService;
import com.hybrid.platform.catalog.CatalogQueryService.SellableVariant;
import com.hybrid.platform.common.AppProperties;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.crypto.Hashing;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.commerce.cart.CartService;
import com.hybrid.platform.commerce.coupon.CouponService;
import com.hybrid.platform.commerce.inventory.InventoryService;
import com.hybrid.platform.commerce.order.OrderRepositories.OrderRepository;
import com.hybrid.platform.commerce.settings.PincodeService;
import com.hybrid.platform.commerce.settings.SettingsService;
import com.hybrid.platform.commerce.settings.ShippingMethod;
import com.hybrid.platform.commerce.settings.StoreSettings;
import com.hybrid.platform.delivery.DeliveryReadinessService;
import com.hybrid.platform.identity.Customer;
import com.hybrid.platform.identity.CustomerRepository;
import com.hybrid.platform.payment.PaymentService;
import com.hybrid.platform.security.CustomerPrincipal;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Turns a cart into an order. Every amount (prices, discount, shipping, COD fee, GST) is computed here from catalog
 * prices and store settings; the request only contributes choices (shipping method, coupon code, payment method,
 * address).
 */
@Service
public class CheckoutService {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9_-]{8,80}$");
    private static final Pattern PHONE = Pattern.compile("^[+0-9 ()-]{6,30}$");
    private static final Pattern POSTAL = Pattern.compile("^[A-Za-z0-9 -]{3,20}$");
    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
    private static final DateTimeFormatter NUMBER_DATE = DateTimeFormatter.ofPattern("yyMMdd").withZone(ZoneOffset.UTC);

    public record AddressInput(String name, String phone, String line1, String line2, String city, String state,
                               String postalCode, String country) {
    }

    public record CheckoutRequest(Long shippingMethodId, AddressInput address, String couponCode, String paymentMethod,
                                  String buyerStateCode, String buyerGstin) {
    }

    public record QuoteRequest(Long shippingMethodId, String couponCode, String paymentMethod, String buyerStateCode,
                               String postalCode) {
    }

    public record QuoteLine(Long variantId, String title, String variantName, String productType, long unitPriceMinor,
                            int quantity, long lineTotalMinor, int gstRateBps) {
    }

    public record Quote(List<QuoteLine> lines, long subtotalMinor, long discountMinor, String couponCode,
                        String couponMessage, long shippingMinor, long codFeeMinor, long taxMinor, long cgstMinor,
                        long sgstMinor, long igstMinor, boolean pricesIncludeTax, long totalMinor, int taxRateBps,
                        String currency, boolean requiresShipping, Long shippingMethodId,
                        List<SettingsService.ShippingMethodView> shippingMethods, boolean codAvailable,
                        String codUnavailableReason, String paymentMethod, String sellerStateCode,
                        List<String> problems) {
    }

    public record PlacedOrder(String orderNumber, String storefront, long totalMinor, String currency, String status,
                              String paymentMethod, PaymentService.PaymentInit payment) {
    }

    private record Priced(List<QuoteLine> lines, Map<Long, SellableVariant> variants, Map<Long, Integer> quantities,
                          long subtotal, boolean requiresShipping, boolean hasDigital, List<String> problems) {
    }

    private record Computed(GstCalculator.Result gst, List<GstCalculator.LineIn> lineInputs, long shipping, long codFee,
                            CouponService.Applied coupon, String couponMessage, boolean codAvailable, String codReason,
                            Long shippingMethodId, ShippingMethod method) {
    }

    private final CartService carts;
    private final CatalogQueryService catalog;
    private final InventoryService inventory;
    private final DeliveryReadinessService deliveryReadiness;
    private final SettingsService settings;
    private final PincodeService pincodes;
    private final CouponService coupons;
    private final PaymentService payments;
    private final OrderRepository orders;
    private final CustomerRepository customers;
    private final ApplicationEventPublisher publisher;
    private final AppProperties props;

    public CheckoutService(CartService carts, CatalogQueryService catalog, InventoryService inventory,
                           DeliveryReadinessService deliveryReadiness, SettingsService settings, PincodeService pincodes,
                           CouponService coupons, PaymentService payments, OrderRepository orders,
                           CustomerRepository customers, ApplicationEventPublisher publisher, AppProperties props) {
        this.carts = carts;
        this.catalog = catalog;
        this.inventory = inventory;
        this.deliveryReadiness = deliveryReadiness;
        this.settings = settings;
        this.pincodes = pincodes;
        this.coupons = coupons;
        this.payments = payments;
        this.orders = orders;
        this.customers = customers;
        this.publisher = publisher;
        this.props = props;
    }

    @Transactional
    public Quote quote(CustomerPrincipal customer, Storefront sf, QuoteRequest req) {
        rejectIfAffiliateOnly();
        QuoteRequest r = req == null ? new QuoteRequest(null, null, null, null, null) : req;
        Priced priced = price(customer.id(), sf);
        StoreSettings s = settings.entity(sf);
        List<SettingsService.ShippingMethodView> methods = priced.requiresShipping() ? settings.activeShippingMethods(sf) : List.of();
        List<String> problems = new ArrayList<>(priced.problems());
        if (priced.requiresShipping() && methods.isEmpty()) {
            problems.add("No shipping method is available. Please contact the store.");
        }
        Long wanted = r.shippingMethodId() != null ? r.shippingMethodId() : methods.isEmpty() ? null : methods.get(0).id();
        boolean cod = "COD".equalsIgnoreCase(r.paymentMethod());
        String buyerState = IndianStates.isValid(r.buyerStateCode()) ? r.buyerStateCode() : null;
        Computed c = compute(sf, s, customer.id(), priced, wanted, r.couponCode(), cod, buyerState, r.postalCode(), false);
        if (cod && !c.codAvailable()) problems.add(c.codReason());
        if (priced.requiresShipping() && SettingsService.isPin(r.postalCode())) {
            var check = pincodes.check(sf, r.postalCode());
            if (!check.serviceable()) problems.add(check.message());
        }
        return new Quote(priced.lines(), c.gst().subtotalMinor(), c.gst().discountMinor(),
                c.coupon() == null ? null : c.coupon().code(), c.couponMessage(), c.shipping(), c.codFee(),
                c.gst().taxMinor(), c.gst().cgstMinor(), c.gst().sgstMinor(), c.gst().igstMinor(), s.isPricesIncludeTax(),
                c.gst().totalMinor(), s.getTaxRateBps(), props.currency(), priced.requiresShipping(), c.shippingMethodId(),
                methods, c.codAvailable(), c.codAvailable() ? null : c.codReason(), cod && c.codAvailable() ? "COD" : "ONLINE",
                s.getSellerStateCode(), problems);
    }

    /** Lets the cart/checkout page validate a coupon before placing the order. */
    @Transactional(readOnly = true)
    public CouponService.Applied checkCoupon(CustomerPrincipal customer, Storefront sf, String code) {
        rejectIfAffiliateOnly();
        Priced priced = price(customer.id(), sf);
        if (priced.lines().isEmpty()) throw ApiException.rule("CART_EMPTY", "Your cart is empty");
        return coupons.evaluate(sf, customer.id(), code, priced.subtotal());
    }

    @Transactional
    public PlacedOrder place(CustomerPrincipal principal, Storefront sf, CheckoutRequest request, String idempotencyKey) {
        rejectIfAffiliateOnly();
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "A valid Idempotency-Key header is required");
        }
        Optional<CustomerOrder> previous = orders.findByCustomerIdAndIdempotencyKey(principal.id(), idempotencyKey);
        if (previous.isPresent()) {
            CustomerOrder o = previous.get();
            if (o.getStorefront() != sf) {
                throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "This checkout key was already used");
            }
            return placed(o, o.isCod() ? null : payments.currentInit(o.getId()).orElse(null));
        }
        Customer customer = customers.findById(principal.id())
                .orElseThrow(() -> ApiException.unauthorized("UNAUTHENTICATED", "Please sign in"));
        if (props.security().requireEmailVerification() && !customer.isEmailVerified()) {
            throw ApiException.forbidden("EMAIL_NOT_VERIFIED", "Please verify your email address before placing an order");
        }
        CheckoutRequest req = request == null ? new CheckoutRequest(null, null, null, null, null, null) : request;

        Priced priced = price(customer.getId(), sf);
        if (priced.lines().isEmpty() && priced.problems().isEmpty()) {
            throw ApiException.rule("CART_EMPTY", "Your cart is empty");
        }
        if (!priced.problems().isEmpty()) {
            throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "CART_HAS_PROBLEMS",
                    "Some items in your cart need attention", Map.<String, Object>of("problems", priced.problems()));
        }
        StoreSettings s = settings.entity(sf);

        String buyerState = blankToNull(req.buyerStateCode());
        if (buyerState == null && req.address() != null) {
            buyerState = IndianStates.codeForName(req.address().state());
        }
        if (buyerState != null && !IndianStates.isValid(buyerState)) {
            throw ApiException.badRequest("INVALID_STATE", "Choose your state from the list");
        }
        String gstin = blankToNull(req.buyerGstin());
        if (gstin != null) {
            gstin = gstin.toUpperCase(Locale.ROOT);
            if (!SettingsService.isGstin(gstin)) throw ApiException.badRequest("INVALID_GSTIN", "GSTIN must be 15 characters, e.g. 27ABCDE1234F1Z5");
        }

        ShippingAddress address = null;
        if (priced.requiresShipping()) {
            if (req.shippingMethodId() == null) {
                throw ApiException.rule("SHIPPING_METHOD_REQUIRED", "Choose a shipping method");
            }
            if (buyerState == null) {
                throw ApiException.badRequest("STATE_REQUIRED", "Choose the delivery state");
            }
            address = validateAddress(req.address(), buyerState);
            if ("IN".equals(address.getCountry())) {
                var check = pincodes.check(sf, address.getPostalCode().replace(" ", ""));
                if (!check.serviceable()) throw ApiException.rule("PINCODE_NOT_SERVICEABLE", check.message());
            }
        } else if (buyerState == null && gstin != null) {
            buyerState = gstin.substring(0, 2);
        }

        boolean cod = "COD".equalsIgnoreCase(req.paymentMethod());
        String postal = address == null ? null : address.getPostalCode().replace(" ", "");
        CustomerOrder order = new CustomerOrder(newOrderNumber(sf), sf, customer.getId(), customer.getEmail(),
                customer.getFullName(), props.currency(), idempotencyKey,
                cod ? null : Instant.now().plus(Duration.ofMinutes(props.checkout().reservationMinutes())));
        List<Map.Entry<Long, Integer>> entries = new ArrayList<>(priced.quantities().entrySet());
        for (Map.Entry<Long, Integer> e : entries) {
            SellableVariant v = priced.variants().get(e.getKey());
            order.addLine(new OrderLine(v.productId(), v.variantId(), v.productType(), v.sku(), v.title(), v.variantName(),
                    v.priceMinor(), e.getValue()));
        }
        orders.saveAndFlush(order);

        Computed c = compute(sf, s, customer.getId(), priced, req.shippingMethodId(), req.couponCode(), cod, buyerState,
                postal, true, order.getId());
        if (cod && !c.codAvailable()) throw ApiException.rule("COD_UNAVAILABLE", c.codReason());

        for (int i = 0; i < order.getLines().size(); i++) {
            OrderLine line = order.getLines().get(i);
            SellableVariant v = priced.variants().get(line.getVariantId());
            String hsn = v.hsnSac() != null ? v.hsnSac() : s.getDefaultHsnSac();
            line.setTax(hsn, c.gst().lines().get(i));
        }
        order.setAmounts(c.gst(), c.shipping(), c.codFee(), s.isPricesIncludeTax());
        order.setCouponCode(c.coupon() == null ? null : c.coupon().code());
        order.setBuyerStateCode(buyerState);
        order.setBuyerGstin(gstin);
        order.setRequiresShipping(priced.requiresShipping());
        order.setShippingMethodName(c.method() == null ? null : c.method().getName());
        order.setShippingAddress(address);
        order.setPaymentMethod(cod ? CustomerOrder.PaymentMethod.COD : CustomerOrder.PaymentMethod.ONLINE);

        for (OrderLine line : order.physicalLines()) {
            inventory.reserve(order.getId(), line.getVariantId(), line.getQuantity(), line.getTitle());
        }
        catalog.lockTypes(order.getLines().stream().map(OrderLine::getProductId).distinct().toList());
        carts.clear(customer.getId(), sf);

        if (cod) {
            inventory.commitForOrder(order.getId());
            order.setStatus(CustomerOrder.Status.CONFIRMED);
            payments.startCashOnDelivery(order.getId(), sf, order.getTotalMinor(), order.getCurrency());
            orders.flush();
            publisher.publishEvent(new OrderEvents.CodOrderConfirmed(order.getId(), order.getOrderNumber(), sf,
                    order.getCustomerEmail(), order.getCustomerName(), order.getTotalMinor(), order.getCurrency()));
            return placed(order, null);
        }
        orders.flush();
        PaymentService.PaymentInit init = payments.start(order.getId(), sf, order.getTotalMinor(), order.getCurrency(),
                order.getOrderNumber());
        return placed(order, init);
    }

    private Computed compute(Storefront sf, StoreSettings s, Long customerId, Priced priced, Long shippingMethodId,
                             String couponCode, boolean cod, String buyerState, String postal, boolean strict) {
        return compute(sf, s, customerId, priced, shippingMethodId, couponCode, cod, buyerState, postal, strict, null);
    }

    private Computed compute(Storefront sf, StoreSettings s, Long customerId, Priced priced, Long shippingMethodId,
                             String couponCode, boolean cod, String buyerState, String postal, boolean strict, Long orderId) {
        CouponService.Applied coupon = null;
        String couponMessage = null;
        if (couponCode != null && !couponCode.isBlank() && !priced.lines().isEmpty()) {
            try {
                coupon = orderId != null
                        ? coupons.reserve(sf, customerId, orderId, couponCode, priced.subtotal())
                        : coupons.evaluate(sf, customerId, couponCode, priced.subtotal());
                couponMessage = coupon.description() != null ? coupon.description() : "Coupon applied";
            } catch (ApiException e) {
                if (strict) throw e;
                couponMessage = e.getMessage();
            }
        }
        long discount = coupon == null ? 0 : coupon.discountMinor();

        ShippingMethod method = null;
        long shipping = 0;
        if (priced.requiresShipping() && shippingMethodId != null) {
            method = settings.requireActiveShippingMethod(sf, shippingMethodId);
            shipping = method.rateFor(priced.subtotal() - discount);
        }

        String codReason = codUnavailableReason(sf, s, priced, postal);
        boolean codAvailable = codReason == null;
        long codFee = cod && codAvailable ? s.getCodFeeMinor() : 0;

        List<GstCalculator.LineIn> inputs = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : priced.quantities().entrySet()) {
            SellableVariant v = priced.variants().get(e.getKey());
            if (v == null || !v.purchasableIn(sf)) continue;
            inputs.add(new GstCalculator.LineIn(v.priceMinor(), e.getValue(), v.gstRateBps() != null ? v.gstRateBps() : s.getTaxRateBps()));
        }
        String sellerState = s.getSellerStateCode();
        String placeOfSupply = buyerState != null ? buyerState : sellerState;
        GstCalculator.Result gst = GstCalculator.compute(inputs, discount, shipping + codFee, s.isPricesIncludeTax(),
                sellerState, placeOfSupply);
        if (cod && codAvailable && s.getCodMaxMinor() != null && gst.totalMinor() > s.getCodMaxMinor()) {
            codAvailable = false;
            codReason = "Cash on delivery is available for orders up to \u20B9" + (s.getCodMaxMinor() / 100) + ".";
            gst = GstCalculator.compute(inputs, discount, shipping, s.isPricesIncludeTax(), sellerState, placeOfSupply);
            codFee = 0;
        }
        return new Computed(gst, inputs, shipping, codFee, coupon, couponMessage, codAvailable, codReason,
                method == null ? null : method.getId(), method);
    }

    private String codUnavailableReason(Storefront sf, StoreSettings s, Priced priced, String postal) {
        if (sf != Storefront.SHOP || !s.isCodEnabled()) return "Cash on delivery is not offered by this store.";
        if (priced.hasDigital() || !priced.requiresShipping()) return "Cash on delivery is only available for orders with physical items only.";
        if (postal != null && SettingsService.isPin(postal) && !pincodes.check(sf, postal).codAvailable()) {
            return "Cash on delivery is not available for PIN code " + postal + ".";
        }
        long subtotal = priced.subtotal();
        if (s.getCodMaxMinor() != null && subtotal > s.getCodMaxMinor()) {
            return "Cash on delivery is available for orders up to \u20B9" + (s.getCodMaxMinor() / 100) + ".";
        }
        return null;
    }

    private void rejectIfAffiliateOnly() {
        if (props.affiliateOnly()) {
            throw ApiException.rule("AFFILIATE_ONLY",
                    "This store is affiliate-only. Products are bought on partner sites — checkout is disabled here.");
        }
    }

    private Priced price(Long customerId, Storefront sf) {
        Map<Long, Integer> quantities = new LinkedHashMap<>(carts.lines(customerId, sf));
        Map<Long, SellableVariant> variants = catalog.variants(quantities.keySet());
        Map<Long, Integer> stock = inventory.availability(quantities.keySet());
        Map<Long, Integer> licensesNeeded = new HashMap<>();
        List<QuoteLine> lines = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        long subtotal = 0;
        boolean shipping = false;
        boolean digital = false;
        for (Map.Entry<Long, Integer> e : quantities.entrySet()) {
            SellableVariant v = variants.get(e.getKey());
            int qty = e.getValue();
            // Partner offers stay in the cart for later; they are bought on the merchant site, not here.
            if (v != null && v.sellingMethod() == SellingMethod.AFFILIATE) {
                continue;
            }
            if (v == null || !v.purchasableIn(sf)) {
                problems.add((v == null ? "An item" : "'" + v.title() + "'") + " is no longer available. Remove it from your cart.");
                continue;
            }
            if (v.productType() == ProductType.PHYSICAL) {
                shipping = true;
                int available = stock.getOrDefault(v.variantId(), 0);
                if (available < qty) {
                    problems.add(available <= 0 ? "'" + v.title() + "' is out of stock."
                            : "Only " + available + " of '" + v.title() + "' left in stock.");
                    continue;
                }
            } else {
                digital = true;
                if (DeliveryReadinessService.needsLicense(v.deliveryKind())) {
                    licensesNeeded.merge(v.productId(), qty, Integer::sum);
                }
            }
            long lineTotal = v.priceMinor() * qty;
            subtotal += lineTotal;
            lines.add(new QuoteLine(v.variantId(), v.title(), v.variantName(), v.productType().name(), v.priceMinor(), qty,
                    lineTotal, v.gstRateBps() != null ? v.gstRateBps() : settings.entity(sf).getTaxRateBps()));
        }
        for (Map.Entry<Long, Integer> e : licensesNeeded.entrySet()) {
            if (deliveryReadiness.availableLicenses(e.getKey()) < e.getValue()) {
                String title = variants.values().stream().filter(v -> v.productId().equals(e.getKey()))
                        .map(SellableVariant::title).findFirst().orElse("A product");
                problems.add("'" + title + "' is temporarily sold out.");
            }
        }
        return new Priced(lines, variants, quantities, subtotal, shipping, digital, problems);
    }

    private ShippingAddress validateAddress(AddressInput a, String stateCode) {
        if (a == null) {
            throw ApiException.rule("ADDRESS_REQUIRED", "A delivery address is required");
        }
        Map<String, String> errors = new LinkedHashMap<>();
        String name = required(a.name(), 120, "name", errors);
        String phone = required(a.phone(), 30, "phone", errors);
        if (phone != null && !PHONE.matcher(phone).matches()) errors.put("phone", "Enter a valid phone number");
        String line1 = required(a.line1(), 200, "line1", errors);
        String line2 = a.line2() == null || a.line2().isBlank() ? null : a.line2().trim();
        if (line2 != null && line2.length() > 200) errors.put("line2", "Too long");
        String city = required(a.city(), 100, "city", errors);
        String state = a.state() == null || a.state().isBlank() ? IndianStates.name(stateCode) : a.state().trim();
        if (state == null) errors.put("state", "Required");
        String postal = required(a.postalCode(), 20, "postalCode", errors);
        String country = a.country() == null || a.country().isBlank() ? "IN" : a.country().trim().toUpperCase(Locale.ROOT);
        if (!COUNTRY.matcher(country).matches()) errors.put("country", "Use a 2-letter country code");
        if (postal != null) {
            boolean ok = "IN".equals(country) ? SettingsService.isPin(postal.replace(" ", "")) : POSTAL.matcher(postal).matches();
            if (!ok) errors.put("postalCode", "IN".equals(country) ? "Enter a valid 6-digit PIN code" : "Enter a valid postal code");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "ADDRESS_INVALID",
                    "Please check the delivery address", Map.<String, Object>of("fields", errors));
        }
        return new ShippingAddress(name, phone, line1, line2, city, state, postal, country);
    }

    private static String required(String value, int max, String field, Map<String, String> errors) {
        if (value == null || value.isBlank()) {
            errors.put(field, "Required");
            return null;
        }
        String v = value.trim();
        if (v.length() > max) {
            errors.put(field, "Too long");
        }
        return v;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String newOrderNumber(Storefront sf) {
        return (sf == Storefront.SHOP ? "S" : "D") + "-" + NUMBER_DATE.format(Instant.now()) + "-" + Hashing.randomCode(8).toUpperCase(Locale.ROOT);
    }

    private static PlacedOrder placed(CustomerOrder o, PaymentService.PaymentInit init) {
        return new PlacedOrder(o.getOrderNumber(), o.getStorefront().path(), o.getTotalMinor(), o.getCurrency(),
                o.getStatus().name(), o.getPaymentMethod().name(), init);
    }
}
