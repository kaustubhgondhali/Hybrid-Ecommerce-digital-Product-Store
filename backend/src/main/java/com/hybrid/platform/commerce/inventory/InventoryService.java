package com.hybrid.platform.commerce.inventory;

import com.hybrid.platform.catalog.CatalogQueryService;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.audit.AuditService;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.commerce.inventory.InventoryRepositories.ItemRepository;
import com.hybrid.platform.commerce.inventory.InventoryRepositories.MovementRepository;
import com.hybrid.platform.commerce.inventory.InventoryRepositories.ReservationRepository;
import com.hybrid.platform.security.AdminPrincipal;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class InventoryService {

    public record StockView(Long variantId, Long productId, String productTitle, String variantName, String sku,
                            int onHand, int reserved, int available, int lowStockThreshold, boolean low) {
    }

    public record MovementView(Long id, int delta, String reason, Long orderId, String note, java.time.Instant createdAt) {
    }

    private final ItemRepository items;
    private final ReservationRepository reservations;
    private final MovementRepository movements;
    private final CatalogQueryService catalog;
    private final AuditService audit;

    public InventoryService(ItemRepository items, ReservationRepository reservations, MovementRepository movements,
                            CatalogQueryService catalog, AuditService audit) {
        this.items = items;
        this.reservations = reservations;
        this.movements = movements;
        this.catalog = catalog;
        this.audit = audit;
    }

    @Transactional
    public void ensureItem(Long variantId, Storefront storefront) {
        if (!items.existsById(variantId)) {
            items.save(new InventoryItem(variantId, storefront));
        }
    }

    @Transactional(readOnly = true)
    public Map<Long, Integer> availability(Collection<Long> variantIds) {
        Map<Long, Integer> result = new HashMap<>();
        items.findAllById(variantIds).forEach(i -> result.put(i.getVariantId(), Math.max(i.available(), 0)));
        return result;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(Long orderId, Long variantId, int quantity, String title) {
        if (items.tryReserve(variantId, quantity) != 1) {
            throw ApiException.rule("OUT_OF_STOCK", "Not enough stock for '" + title + "'");
        }
        reservations.save(new InventoryReservation(orderId, variantId, quantity));
    }

    /** Re-reserve after the original reservation expired (late payment). Returns false if stock is gone. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryReserve(Long orderId, Long variantId, int quantity) {
        if (items.tryReserve(variantId, quantity) != 1) {
            return false;
        }
        reservations.save(new InventoryReservation(orderId, variantId, quantity));
        return true;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void commitForOrder(Long orderId) {
        for (InventoryReservation r : reservations.findByOrderIdAndStatus(orderId, InventoryReservation.Status.ACTIVE)) {
            if (items.commit(r.getVariantId(), r.getQuantity()) != 1) {
                throw new IllegalStateException("Reservation " + r.getVariantId() + " could not be committed");
            }
            r.setStatus(InventoryReservation.Status.COMMITTED);
            movements.save(new InventoryMovement(r.getVariantId(), -r.getQuantity(), InventoryMovement.Reason.SALE, orderId, null, null));
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseForOrder(Long orderId) {
        for (InventoryReservation r : reservations.findByOrderIdAndStatus(orderId, InventoryReservation.Status.ACTIVE)) {
            items.release(r.getVariantId(), r.getQuantity());
            r.setStatus(InventoryReservation.Status.RELEASED);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void restock(Long orderId, Long variantId, int quantity) {
        items.restock(variantId, quantity);
        movements.save(new InventoryMovement(variantId, quantity, InventoryMovement.Reason.RESTOCK, orderId, null, "Order cancelled/refunded"));
    }

    @Transactional
    public StockView adjust(AdminPrincipal admin, Long variantId, int delta, InventoryMovement.Reason reason, String note,
                            Integer lowStockThreshold) {
        InventoryItem item = items.findById(variantId)
                .filter(i -> i.getStorefront() == admin.storefront())
                .orElseThrow(() -> ApiException.notFound("INVENTORY_NOT_FOUND", "Inventory item not found"));
        if (delta != 0) {
            int newOnHand = item.getOnHand() + delta;
            if (newOnHand < item.getReserved()) {
                throw ApiException.rule("STOCK_BELOW_RESERVED",
                        "Stock cannot go below the " + item.getReserved() + " units reserved by pending orders");
            }
            item.setOnHand(newOnHand);
            movements.save(new InventoryMovement(variantId, delta, reason, null, admin.id(), note));
        }
        if (lowStockThreshold != null && lowStockThreshold >= 0) {
            item.setLowStockThreshold(lowStockThreshold);
        }
        audit.record(admin, "INVENTORY_ADJUSTED", "Variant", variantId, "delta=" + delta + " reason=" + reason + " note=" + note);
        return toViews(List.of(item)).get(0);
    }

    @Transactional(readOnly = true)
    public List<StockView> list(Storefront sf) {
        return toViews(items.findByStorefront(sf));
    }

    @Transactional(readOnly = true)
    public List<MovementView> movements(Storefront sf, Long variantId) {
        items.findById(variantId).filter(i -> i.getStorefront() == sf)
                .orElseThrow(() -> ApiException.notFound("INVENTORY_NOT_FOUND", "Inventory item not found"));
        return movements.findByVariantIdOrderByCreatedAtDesc(variantId, PageRequest.of(0, 100)).stream()
                .map(m -> new MovementView(m.getId(), m.getDelta(), m.getReason().name(), m.getOrderId(), m.getNote(), m.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public long lowStockCount(Storefront sf) {
        return items.countLowStock(sf);
    }

    private List<StockView> toViews(List<InventoryItem> list) {
        Map<Long, CatalogQueryService.SellableVariant> info = catalog.variants(list.stream().map(InventoryItem::getVariantId).toList());
        List<StockView> views = new ArrayList<>();
        for (InventoryItem i : list) {
            var v = info.get(i.getVariantId());
            if (v == null) continue;
            views.add(new StockView(i.getVariantId(), v.productId(), v.title(), v.variantName(), v.sku(), i.getOnHand(),
                    i.getReserved(), i.available(), i.getLowStockThreshold(), i.available() <= i.getLowStockThreshold()));
        }
        views.sort(Comparator.comparing(StockView::productTitle).thenComparing(StockView::variantName));
        return views;
    }
}
