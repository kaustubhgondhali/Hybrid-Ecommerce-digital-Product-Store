package com.hybrid.platform.commerce;

import com.hybrid.platform.affiliate.AffiliateService;
import com.hybrid.platform.catalog.CatalogEnums.ProductStatus;
import com.hybrid.platform.catalog.ProductRepository;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.commerce.inventory.InventoryService;
import com.hybrid.platform.commerce.order.CustomerOrder;
import com.hybrid.platform.commerce.order.OrderRepositories.OrderRepository;
import com.hybrid.platform.commerce.order.OrderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Service
public class DashboardService {

    private static final Set<CustomerOrder.Status> REVENUE_STATUSES = EnumSet.of(CustomerOrder.Status.PAID,
            CustomerOrder.Status.SHIPPED, CustomerOrder.Status.DELIVERED, CustomerOrder.Status.COMPLETED,
            CustomerOrder.Status.PARTIALLY_REFUNDED);

    public record Dashboard(long ordersToday, long revenue30dMinor, long paidOrders30d, long pendingPayment,
                            long awaitingShipment, long needsAttention, long publishedProducts, long lowStockItems,
                            long affiliateClicks7d, String currency, List<OrderService.AdminOrderSummary> recentOrders) {
    }

    private final OrderRepository orders;
    private final ProductRepository products;
    private final InventoryService inventory;
    private final AffiliateService affiliate;

    public DashboardService(OrderRepository orders, ProductRepository products, InventoryService inventory,
                            AffiliateService affiliate) {
        this.orders = orders;
        this.products = products;
        this.inventory = inventory;
        this.affiliate = affiliate;
    }

    @Transactional(readOnly = true)
    public Dashboard get(Storefront sf, String currency) {
        Instant now = Instant.now();
        Instant startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant days30 = now.minus(Duration.ofDays(30));
        List<OrderService.AdminOrderSummary> recent = orders.findTop8ByStorefrontOrderByPlacedAtDesc(sf).stream()
                .map(o -> new OrderService.AdminOrderSummary(o.getId(), o.getOrderNumber(), o.getStatus().name(),
                        o.getCustomerName(), o.getCustomerEmail(), o.getTotalMinor(), o.getCurrency(),
                        o.isRequiresShipping(), o.getPlacedAt(), o.getPaymentMethod().name()))
                .toList();
        return new Dashboard(
                orders.countByStorefrontAndPlacedAtAfter(sf, startOfDay),
                orders.sumPaidSince(sf, days30, REVENUE_STATUSES),
                orders.countPaidSince(sf, days30, REVENUE_STATUSES),
                orders.countByStorefrontAndStatus(sf, CustomerOrder.Status.PENDING_PAYMENT),
                orders.countByStorefrontAndStatus(sf, CustomerOrder.Status.PAID)
                        + orders.countByStorefrontAndStatus(sf, CustomerOrder.Status.CONFIRMED),
                orders.countByStorefrontAndStatus(sf, CustomerOrder.Status.NEEDS_ATTENTION),
                products.countByStorefrontAndStatus(sf, ProductStatus.PUBLISHED),
                inventory.lowStockCount(sf),
                affiliate.clicksSince(sf, now.minus(Duration.ofDays(7))),
                currency, recent);
    }
}
