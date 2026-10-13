package com.hybrid.platform.commerce.cart;

import com.hybrid.platform.affiliate.AffiliateService;
import com.hybrid.platform.catalog.CatalogEnums.ProductType;
import com.hybrid.platform.catalog.CatalogEnums.SellingMethod;
import com.hybrid.platform.catalog.CatalogQueryService;
import com.hybrid.platform.catalog.CatalogQueryService.SellableVariant;
import com.hybrid.platform.common.AppProperties;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.commerce.inventory.InventoryService;
import com.hybrid.platform.commerce.settings.SettingsService;
import com.hybrid.platform.security.CustomerPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The cart stores only variant ids and quantities. Prices and availability are always re-read from the
 * catalog (and affiliate offers), so nothing a browser sends can change what is shown.
 * Affiliate lines are a "buy later" list — checkout never charges them; shoppers use the partner link.
 */
@Service
public class CartService {

    public static final int MAX_DISTINCT_ITEMS = 50;

    public record CartLine(Long itemId, Long productId, Long variantId, String slug, String title, String variantName,
                           String productType, String sellingMethod, String imageUrl, Long unitPriceMinor, int quantity,
                           long lineTotalMinor, Integer available, String merchantName, String goUrl, String problem) {
    }

    /**
     * {@code hasDirectItems} means items paid for here (own stock or dropship) and {@code checkoutSubtotalMinor} is their
     * total; partner (affiliate) items are bought on the partner's site and never go through checkout.
     */
    public record CartView(String storefront, List<CartLine> items, long subtotalMinor, int itemCount,
                           boolean requiresShipping, boolean hasProblems, boolean affiliateOnly,
                           boolean hasDirectItems, boolean hasAffiliateItems, long checkoutSubtotalMinor) {
    }

    private final CartRepository carts;
    private final CatalogQueryService catalog;
    private final InventoryService inventory;
    private final AffiliateService affiliate;
    private final SettingsService settings;
    private final AppProperties props;

    public CartService(CartRepository carts, CatalogQueryService catalog, InventoryService inventory,
                       AffiliateService affiliate, SettingsService settings, AppProperties props) {
        this.carts = carts;
        this.catalog = catalog;
        this.inventory = inventory;
        this.affiliate = affiliate;
        this.settings = settings;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public CartView view(CustomerPrincipal customer, Storefront sf) {
        return carts.findByCustomerIdAndStorefront(customer.id(), sf)
                .map(this::toView)
                .orElseGet(() -> new CartView(sf.path(), List.of(), 0, 0, false, false, settings.affiliateOnly(sf),
                        false, false, 0));
    }

    @Transactional
    public CartView add(CustomerPrincipal customer, Storefront sf, Long variantId, int quantity) {
        if (variantId == null) {
            throw ApiException.badRequest("VARIANT_REQUIRED", "Choose a product option");
        }
        requireQuantity(quantity);
        SellableVariant v = catalog.variants(List.of(variantId)).get(variantId);
        if (v == null || v.storefront() != sf) {
            throw ApiException.notFound("PRODUCT_NOT_FOUND", "Product not found");
        }
        if (!v.cartableIn(sf)) {
            throw ApiException.rule("NOT_AVAILABLE", "This product is not available");
        }
        if (v.sellingMethod().onSite() && settings.affiliateOnly(sf)) {
            throw ApiException.rule("AFFILIATE_ONLY", "This product is not available for purchase");
        }
        if (v.sellingMethod() == SellingMethod.AFFILIATE) {
            var offer = affiliate.offers(List.of(v.productId())).get(v.productId());
            if (offer == null) {
                throw ApiException.rule("AFFILIATE_UNAVAILABLE", "This partner offer is currently unavailable");
            }
        } else if (!v.purchasableIn(sf)) {
            throw ApiException.rule("NOT_AVAILABLE", "This product is not available for purchase");
        }
        Cart cart = carts.findByCustomerIdAndStorefront(customer.id(), sf)
                .orElseGet(() -> carts.save(new Cart(customer.id(), sf)));
        CartItem existing = cart.findByVariant(variantId).orElse(null);
        int newQuantity = (existing == null ? 0 : existing.getQuantity()) + quantity;
        requireQuantity(newQuantity);
        checkStock(v, newQuantity);
        if (existing != null) {
            existing.setQuantity(newQuantity);
            cart.touch();
        } else {
            if (cart.getItems().size() >= MAX_DISTINCT_ITEMS) {
                throw ApiException.rule("CART_FULL", "Your cart can hold at most " + MAX_DISTINCT_ITEMS + " different items");
            }
            cart.addItem(v.productId(), variantId, quantity);
        }
        return toView(carts.save(cart));
    }

    @Transactional
    public CartView update(CustomerPrincipal customer, Storefront sf, Long itemId, int quantity) {
        Cart cart = requireCart(customer, sf);
        CartItem item = cart.findItem(itemId).orElseThrow(() -> ApiException.notFound("CART_ITEM_NOT_FOUND", "Cart item not found"));
        if (quantity <= 0) {
            cart.removeItem(item);
        } else {
            requireQuantity(quantity);
            SellableVariant v = catalog.variants(List.of(item.getVariantId())).get(item.getVariantId());
            if (v != null && quantity > item.getQuantity()) {
                checkStock(v, quantity);
            }
            item.setQuantity(quantity);
            cart.touch();
        }
        return toView(cart);
    }

    @Transactional
    public CartView remove(CustomerPrincipal customer, Storefront sf, Long itemId) {
        Cart cart = requireCart(customer, sf);
        cart.findItem(itemId).ifPresent(cart::removeItem);
        return toView(cart);
    }

    @Transactional
    public void clear(Long customerId, Storefront sf) {
        carts.findByCustomerIdAndStorefront(customerId, sf).ifPresent(Cart::clear);
    }

    /** Removes the checked-out items; partner offers stay in the cart for the shopper to buy on the partner site. */
    @Transactional
    public void removeVariants(Long customerId, Storefront sf, java.util.Collection<Long> variantIds) {
        carts.findByCustomerIdAndStorefront(customerId, sf).ifPresent(cart -> {
            List<CartItem> gone = cart.getItems().stream().filter(i -> variantIds.contains(i.getVariantId())).toList();
            gone.forEach(cart::removeItem);
        });
    }

    /** Raw lines for checkout: variant id and quantity only. */
    @Transactional(readOnly = true)
    public Map<Long, Integer> lines(Long customerId, Storefront sf) {
        Map<Long, Integer> result = new java.util.LinkedHashMap<>();
        carts.findByCustomerIdAndStorefront(customerId, sf)
                .ifPresent(c -> c.getItems().forEach(i -> result.put(i.getVariantId(), i.getQuantity())));
        return result;
    }

    private Cart requireCart(CustomerPrincipal customer, Storefront sf) {
        return carts.findByCustomerIdAndStorefront(customer.id(), sf)
                .orElseThrow(() -> ApiException.notFound("CART_NOT_FOUND", "Your cart is empty"));
    }

    private void requireQuantity(int quantity) {
        int max = props.checkout().maxQuantityPerLine();
        if (quantity < 1 || quantity > max) {
            throw ApiException.rule("INVALID_QUANTITY", "Quantity must be between 1 and " + max);
        }
    }

    private void checkStock(SellableVariant v, int quantity) {
        if (!v.ownStock()) {
            return;
        }
        int available = inventory.availability(List.of(v.variantId())).getOrDefault(v.variantId(), 0);
        if (available < quantity) {
            throw ApiException.rule("OUT_OF_STOCK", available <= 0
                    ? "'" + v.title() + "' is out of stock"
                    : "Only " + available + " of '" + v.title() + "' left in stock");
        }
    }

    private CartView toView(Cart cart) {
        List<Long> ids = cart.getItems().stream().map(CartItem::getVariantId).toList();
        Map<Long, SellableVariant> info = catalog.variants(ids);
        Map<Long, Integer> stock = inventory.availability(ids);
        List<Long> affiliateProductIds = info.values().stream()
                .filter(v -> v.sellingMethod() == SellingMethod.AFFILIATE)
                .map(SellableVariant::productId).distinct().toList();
        Map<Long, AffiliateService.OfferView> offers = affiliate.offers(affiliateProductIds);
        boolean affiliateOnly = settings.affiliateOnly(cart.getStorefront());
        List<CartLine> lines = new ArrayList<>();
        long subtotal = 0;
        long checkoutSubtotal = 0;
        int count = 0;
        boolean shipping = false;
        boolean problems = false;
        boolean hasDirect = false;
        boolean hasAffiliate = false;
        for (CartItem item : cart.getItems()) {
            SellableVariant v = info.get(item.getVariantId());
            String problem = null;
            Integer available = null;
            String merchant = null;
            String goUrl = null;
            Long unit = v == null ? null : v.priceMinor();
            if (v == null || !v.cartableIn(cart.getStorefront())) {
                problem = "No longer available";
            } else if (v.sellingMethod() == SellingMethod.AFFILIATE) {
                hasAffiliate = true;
                var offer = offers.get(v.productId());
                if (offer == null) {
                    problem = "Partner offer unavailable";
                } else {
                    merchant = offer.merchantName();
                    goUrl = offer.goUrl();
                    unit = offer.displayPriceMinor();
                }
            } else {
                hasDirect = true;
                if (affiliateOnly || !v.purchasableIn(cart.getStorefront())) {
                    problem = "No longer available";
                } else if (v.ownStock()) {
                    available = stock.getOrDefault(v.variantId(), 0);
                    if (available < item.getQuantity()) {
                        problem = available <= 0 ? "Out of stock" : "Only " + available + " left";
                    }
                }
            }
            long lineTotal = unit == null ? 0 : unit * item.getQuantity();
            if (problem == null) {
                subtotal += lineTotal;
                count += item.getQuantity();
                shipping |= v.needsShipping();
                if (v.sellingMethod().onSite()) checkoutSubtotal += lineTotal;
            } else {
                problems = true;
            }
            lines.add(new CartLine(item.getId(), item.getProductId(), item.getVariantId(), v == null ? null : v.slug(),
                    v == null ? "Unavailable product" : v.title(), v == null ? "" : v.variantName(),
                    v == null ? null : v.productType().name(), v == null ? null : v.sellingMethod().name(),
                    v == null ? null : v.imageUrl(), unit, item.getQuantity(), lineTotal, available, merchant, goUrl, problem));
        }
        return new CartView(cart.getStorefront().path(), lines, subtotal, count, shipping, problems, affiliateOnly,
                hasDirect, hasAffiliate, checkoutSubtotal);
    }
}
