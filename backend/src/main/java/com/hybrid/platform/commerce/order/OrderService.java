package com.hybrid.platform.commerce.order;

import com.hybrid.platform.catalog.CatalogEnums;
import com.hybrid.platform.common.PageResponse;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.audit.AuditService;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.commerce.coupon.CouponService;
import com.hybrid.platform.commerce.inventory.InventoryService;
import com.hybrid.platform.commerce.order.OrderRepositories.OrderRepository;
import com.hybrid.platform.commerce.order.OrderRepositories.ShipmentRepository;
import com.hybrid.platform.payment.PaymentCallbackService;
import com.hybrid.platform.payment.PaymentEvents;
import com.hybrid.platform.payment.PaymentService;
import com.hybrid.platform.security.AdminPrincipal;
import com.hybrid.platform.security.CustomerPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    public record LineView(Long id, Long productId, String title, String variantName, String sku, String productType,
                           long unitPriceMinor, int quantity, long lineTotalMinor, String fulfillmentStatus,
                           String hsnSac, int gstRateBps, long discountMinor, long taxableMinor, long taxMinor) {
    }

    public record ShipmentView(Long id, String carrier, String trackingNumber, String trackingUrl, String status,
                               Instant shippedAt, Instant deliveredAt) {
    }

    public record OrderSummary(String orderNumber, String storefront, String status, long totalMinor, String currency,
                               int itemCount, Instant placedAt) {
    }

    public record OrderDetail(String orderNumber, String storefront, String status, String currency, long subtotalMinor,
                              long shippingMinor, long taxMinor, long totalMinor, boolean requiresShipping,
                              String shippingMethodName, ShippingAddress shippingAddress, Instant placedAt,
                              Instant expiresAt, Instant paidAt, Instant cancelledAt, List<LineView> lines,
                              List<ShipmentView> shipments, String invoiceNumber, String paymentStatus,
                              String paymentFailureReason, boolean canPay, String paymentMethod, long discountMinor,
                              String couponCode, long codFeeMinor, long cgstMinor, long sgstMinor, long igstMinor,
                              boolean pricesIncludeTax, String buyerStateCode, String buyerGstin) {
    }

    public record AdminOrderSummary(Long id, String orderNumber, String status, String customerName, String customerEmail,
                                    long totalMinor, String currency, boolean requiresShipping, Instant placedAt,
                                    String paymentMethod) {
    }

    public record AdminActions(boolean canCancel, boolean canRefund, long refundableMinor, boolean canShip,
                               boolean canMarkDelivered) {
    }

    public record AdminOrderDetail(Long id, Long customerId, String customerEmail, String customerName, OrderDetail order,
                                   PaymentService.PaymentView payment, AdminActions actions) {
    }

    public record PayInfo(String orderNumber, String storefront, String status, long totalMinor, String currency,
                          Instant expiresAt, boolean demo, String paymentStatus, String failureReason,
                          PaymentService.PaymentInit payment) {
    }

    private final OrderRepository orders;
    private final ShipmentRepository shipments;
    private final InventoryService inventory;
    private final InvoiceService invoices;
    private final PaymentService payments;
    private final PaymentCallbackService paymentCallbacks;
    private final CouponService coupons;
    private final AuditService audit;
    private final ApplicationEventPublisher publisher;
    private final TransactionTemplate tx;

    public OrderService(OrderRepository orders, ShipmentRepository shipments, InventoryService inventory,
                        InvoiceService invoices, PaymentService payments, PaymentCallbackService paymentCallbacks,
                        CouponService coupons, AuditService audit,
                        ApplicationEventPublisher publisher, PlatformTransactionManager txManager) {
        this.orders = orders;
        this.shipments = shipments;
        this.inventory = inventory;
        this.invoices = invoices;
        this.payments = payments;
        this.paymentCallbacks = paymentCallbacks;
        this.coupons = coupons;
        this.audit = audit;
        this.publisher = publisher;
        this.tx = new TransactionTemplate(txManager);
    }

    // ------------------------------------------------------------------ payment lifecycle

    /** Runs inside the payment transaction: the order becomes PAID atomically with the payment record. */
    @EventListener
    @Transactional
    public void onPaymentSucceeded(PaymentEvents.PaymentSucceeded event) {
        CustomerOrder order = orders.lockById(event.orderId())
                .orElseThrow(() -> new IllegalStateException("Payment for unknown order " + event.orderId()));
        if (order.getStatus().isPaid() || order.getStatus() == CustomerOrder.Status.REFUNDED) {
            return;
        }
        if (event.amountMinor() != order.getTotalMinor()) {
            log.error("Order {} paid {} but total is {}", order.getOrderNumber(), event.amountMinor(), order.getTotalMinor());
            markNeedsAttention(order);
            return;
        }
        if (order.getStatus() == CustomerOrder.Status.PENDING_PAYMENT) {
            inventory.commitForOrder(order.getId());
        } else if (!reReserve(order)) {
            log.warn("Late payment for {} but stock is no longer available; refund required", order.getOrderNumber());
            markNeedsAttention(order);
            return;
        } else {
            inventory.commitForOrder(order.getId());
        }
        order.setPaidAt(Instant.now());
        order.setExpiresAt(null);
        order.setCancelledAt(null);
        List<OrderEvents.DigitalLine> digital = new ArrayList<>();
        for (OrderLine line : order.digitalLines()) {
            line.setFulfillmentStatus(OrderLine.Fulfillment.DELIVERED_DIGITAL);
            digital.add(new OrderEvents.DigitalLine(line.getId(), line.getProductId(), line.getTitle(), line.getQuantity()));
        }
        order.setStatus(order.isRequiresShipping() ? CustomerOrder.Status.PAID : CustomerOrder.Status.COMPLETED);
        invoices.issue(order);
        coupons.markUsed(order.getId());
        publisher.publishEvent(new OrderEvents.OrderPaid(order.getId(), order.getOrderNumber(), order.getStorefront(),
                order.getCustomerId(), order.getCustomerEmail(), order.getCustomerName(), order.getTotalMinor(),
                order.getCurrency(), order.isRequiresShipping(), digital));
    }

    private boolean reReserve(CustomerOrder order) {
        for (OrderLine line : order.stockLines()) {
            if (!inventory.tryReserve(order.getId(), line.getVariantId(), line.getQuantity())) {
                inventory.releaseForOrder(order.getId());
                return false;
            }
        }
        return true;
    }

    private void markNeedsAttention(CustomerOrder order) {
        order.setStatus(CustomerOrder.Status.NEEDS_ATTENTION);
        order.setPaidAt(Instant.now());
        order.setExpiresAt(null);
    }

    @Scheduled(fixedDelayString = "${app.checkout.expiry-sweep-ms:60000}", initialDelay = 30000)
    public void expireUnpaidOrders() {
        List<Long> ids = orders.findIdsByStatusExpiringBefore(CustomerOrder.Status.PENDING_PAYMENT, Instant.now(),
                PageRequest.of(0, 200));
        for (Long id : ids) {
            try {
                tx.executeWithoutResult(s -> expire(id));
            } catch (RuntimeException e) {
                log.warn("Could not expire order {}: {}", id, e.getMessage());
            }
        }
    }

    void expire(Long orderId) {
        CustomerOrder order = orders.lockById(orderId).orElse(null);
        if (order == null || order.getStatus() != CustomerOrder.Status.PENDING_PAYMENT
                || order.getExpiresAt() == null || order.getExpiresAt().isAfter(Instant.now())) {
            return;
        }
        inventory.releaseForOrder(orderId);
        payments.cancelIfPending(orderId);
        coupons.release(orderId);
        order.setStatus(CustomerOrder.Status.EXPIRED);
        order.setCancelledAt(Instant.now());
    }

    // ------------------------------------------------------------------ customer

    @Transactional(readOnly = true)
    public PageResponse<OrderSummary> customerOrders(CustomerPrincipal customer, Storefront sf, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50));
        var result = sf == null ? orders.findByCustomerIdOrderByPlacedAtDesc(customer.id(), pr)
                : orders.findByCustomerIdAndStorefrontOrderByPlacedAtDesc(customer.id(), sf, pr);
        return PageResponse.of(result, o -> new OrderSummary(o.getOrderNumber(), o.getStorefront().path(), o.getStatus().name(),
                o.getTotalMinor(), o.getCurrency(), o.getLines().stream().mapToInt(OrderLine::getQuantity).sum(), o.getPlacedAt()));
    }

    @Transactional(readOnly = true)
    public OrderDetail customerOrder(CustomerPrincipal customer, String orderNumber) {
        return detail(owned(customer, orderNumber));
    }

    @Transactional(readOnly = true)
    public InvoiceService.InvoiceView customerInvoice(CustomerPrincipal customer, String orderNumber) {
        return invoices.view(owned(customer, orderNumber));
    }

    @Transactional(readOnly = true)
    public PayInfo payInfo(CustomerPrincipal customer, String orderNumber) {
        CustomerOrder o = owned(customer, orderNumber);
        var payment = payments.forOrder(o.getId());
        return new PayInfo(o.getOrderNumber(), o.getStorefront().path(), o.getStatus().name(), o.getTotalMinor(),
                o.getCurrency(), o.getExpiresAt(), payments.isDemo(), payment.map(PaymentService.PaymentView::status).orElse(null),
                payment.map(PaymentService.PaymentView::failureReason).orElse(null),
                canPay(o) ? payments.currentInit(o.getId()).orElse(null) : null);
    }

    @Transactional
    public PayInfo retryPayment(CustomerPrincipal customer, String orderNumber) {
        CustomerOrder o = owned(customer, orderNumber);
        if (!canPay(o)) {
            throw ApiException.conflict("ORDER_NOT_PAYABLE", "This order can no longer be paid");
        }
        payments.retry(o.getId());
        return payInfo(customer, orderNumber);
    }

    /**
     * The customer returned from the gateway's checkout. Not transactional: the gateway is called first, then the
     * outcome is recorded. Accepted even after the order expired, because the money may already have been taken.
     */
    public PayInfo confirmPayment(CustomerPrincipal customer, String orderNumber, Map<String, String> params) {
        CustomerOrder o = owned(customer, orderNumber);
        paymentCallbacks.confirmCheckoutReturn(o.getId(), params == null ? Map.of() : params);
        return payInfo(customer, orderNumber);
    }

    /** Demo provider only: simulates the gateway confirming or declining the payment. */
    @Transactional
    public PayInfo completeDemoPayment(CustomerPrincipal customer, String orderNumber, boolean success) {
        CustomerOrder o = owned(customer, orderNumber);
        if (!canPay(o)) {
            throw ApiException.conflict("ORDER_NOT_PAYABLE", "This order can no longer be paid");
        }
        payments.simulateDemo(o.getId(), success);
        return payInfo(customer, orderNumber);
    }

    private CustomerOrder owned(CustomerPrincipal customer, String orderNumber) {
        return orders.findByOrderNumber(orderNumber)
                .filter(o -> o.getCustomerId().equals(customer.id()))
                .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "Order not found"));
    }

    private static boolean canPay(CustomerOrder o) {
        return o.getStatus() == CustomerOrder.Status.PENDING_PAYMENT
                && o.getExpiresAt() != null && o.getExpiresAt().isAfter(Instant.now());
    }

    // ------------------------------------------------------------------ admin

    @Transactional(readOnly = true)
    public PageResponse<AdminOrderSummary> adminList(Storefront sf, CustomerOrder.Status status, String q,
                                                     CatalogEnums.SellingMethod method, int page, int size) {
        String query = q == null || q.isBlank() ? null : q.trim();
        return PageResponse.of(orders.search(sf, status, query, method, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100))),
                o -> new AdminOrderSummary(o.getId(), o.getOrderNumber(), o.getStatus().name(), o.getCustomerName(),
                        o.getCustomerEmail(), o.getTotalMinor(), o.getCurrency(), o.isRequiresShipping(), o.getPlacedAt(),
                        o.getPaymentMethod().name()));
    }

    @Transactional(readOnly = true)
    public AdminOrderDetail adminDetail(Storefront sf, Long id) {
        return adminView(adminOrder(sf, id));
    }

    @Transactional(readOnly = true)
    public InvoiceService.InvoiceView adminInvoice(Storefront sf, Long id) {
        return invoices.view(adminOrder(sf, id));
    }

    @Transactional
    public AdminOrderDetail cancel(AdminPrincipal admin, Long id, String reason) {
        CustomerOrder o = lockAdminOrder(admin.storefront(), id);
        if (!cancellable(o)) {
            throw ApiException.conflict("ORDER_NOT_CANCELLABLE",
                    o.getStatus().isPaid() ? "Paid orders are cancelled by issuing a refund" : "This order cannot be cancelled");
        }
        if (o.getStatus() == CustomerOrder.Status.PENDING_PAYMENT) {
            inventory.releaseForOrder(o.getId());
        } else {
            for (OrderLine line : o.physicalLines()) {
                if (line.isOwnStock()) inventory.restock(o.getId(), line.getVariantId(), line.getQuantity());
                line.setFulfillmentStatus(OrderLine.Fulfillment.CANCELLED);
            }
        }
        payments.cancelIfPending(o.getId());
        coupons.release(o.getId());
        o.setStatus(CustomerOrder.Status.CANCELLED);
        o.setCancelledAt(Instant.now());
        String why = reason == null || reason.isBlank() ? "Cancelled by the store" : reason.trim();
        audit.record(admin, "ORDER_CANCELLED", "Order", o.getOrderNumber(), why);
        publisher.publishEvent(new OrderEvents.OrderCancelled(o.getId(), o.getOrderNumber(), o.getStorefront(),
                o.getCustomerEmail(), o.getCustomerName(), why));
        return adminView(o);
    }

    @Transactional
    public AdminOrderDetail refund(AdminPrincipal admin, Long id, Long amountMinor, boolean restock, String reason) {
        CustomerOrder o = lockAdminOrder(admin.storefront(), id);
        if (!o.getStatus().isPaid()) {
            throw ApiException.conflict("ORDER_NOT_REFUNDABLE", "Only paid orders can be refunded");
        }
        long refunded = payments.forOrder(o.getId()).map(PaymentService.PaymentView::refundedMinor).orElse(0L);
        long remaining = o.getTotalMinor() - refunded;
        long amount = amountMinor == null ? remaining : amountMinor;
        payments.refund(o.getId(), amount);
        boolean full = refunded + amount >= o.getTotalMinor();
        if (full) {
            boolean stockWasCommitted = o.getStatus() != CustomerOrder.Status.NEEDS_ATTENTION;
            for (OrderLine line : o.getLines()) {
                if (line.isOwnStock() && restock && stockWasCommitted) {
                    inventory.restock(o.getId(), line.getVariantId(), line.getQuantity());
                }
                line.setFulfillmentStatus(OrderLine.Fulfillment.REFUNDED);
            }
            o.setStatus(CustomerOrder.Status.REFUNDED);
        } else {
            o.setStatus(CustomerOrder.Status.PARTIALLY_REFUNDED);
        }
        audit.record(admin, "ORDER_REFUNDED", "Order", o.getOrderNumber(),
                "amount=" + amount + " full=" + full + " restock=" + restock + (reason == null ? "" : " reason=" + reason));
        publisher.publishEvent(new OrderEvents.OrderRefunded(o.getId(), o.getOrderNumber(), o.getStorefront(),
                o.getCustomerEmail(), o.getCustomerName(), amount, o.getCurrency(), full));
        return adminView(o);
    }

    @Transactional
    public AdminOrderDetail ship(AdminPrincipal admin, Long id, String carrier, String trackingNumber, String trackingUrl) {
        CustomerOrder o = lockAdminOrder(admin.storefront(), id);
        if (!shippable(o)) {
            throw ApiException.conflict("ORDER_NOT_SHIPPABLE", "Only paid (or confirmed cash-on-delivery) orders with physical items can be shipped");
        }
        if (carrier == null || carrier.isBlank() || carrier.trim().length() > 80) {
            throw ApiException.badRequest("CARRIER_REQUIRED", "Carrier is required (max 80 characters)");
        }
        String tracking = trackingNumber == null || trackingNumber.isBlank() ? null : trackingNumber.trim();
        if (tracking != null && tracking.length() > 120) {
            throw ApiException.badRequest("TRACKING_INVALID", "Tracking number is too long");
        }
        String url = validateTrackingUrl(trackingUrl);
        shipments.save(new Shipment(o.getId(), carrier.trim(), tracking, url));
        o.physicalLines().forEach(l -> l.setFulfillmentStatus(OrderLine.Fulfillment.SHIPPED));
        o.setStatus(CustomerOrder.Status.SHIPPED);
        audit.record(admin, "ORDER_SHIPPED", "Order", o.getOrderNumber(), carrier.trim() + " " + (tracking == null ? "" : tracking));
        publisher.publishEvent(new OrderEvents.ShipmentDispatched(o.getId(), o.getOrderNumber(), o.getStorefront(),
                o.getCustomerEmail(), o.getCustomerName(), carrier.trim(), tracking, url));
        return adminView(o);
    }

    @Transactional
    public AdminOrderDetail markDelivered(AdminPrincipal admin, Long id) {
        CustomerOrder o = lockAdminOrder(admin.storefront(), id);
        if (o.getStatus() != CustomerOrder.Status.SHIPPED) {
            throw ApiException.conflict("ORDER_NOT_SHIPPED", "Only shipped orders can be marked delivered");
        }
        shipments.findByOrderIdOrderByCreatedAtAsc(o.getId()).forEach(Shipment::markDelivered);
        o.physicalLines().forEach(l -> l.setFulfillmentStatus(OrderLine.Fulfillment.DELIVERED));
        o.setStatus(CustomerOrder.Status.DELIVERED);
        if (o.isCod() && payments.recordCashCollected(o.getId())) {
            o.setPaidAt(Instant.now());
            invoices.issue(o);
            coupons.markUsed(o.getId());
        }
        audit.record(admin, "ORDER_DELIVERED", "Order", o.getOrderNumber(), o.isCod() ? "cash collected" : null);
        return adminView(o);
    }

    private static boolean cancellable(CustomerOrder o) {
        return o.getStatus() == CustomerOrder.Status.PENDING_PAYMENT || o.getStatus() == CustomerOrder.Status.CONFIRMED
                || (o.isCod() && o.getStatus() == CustomerOrder.Status.SHIPPED && o.getPaidAt() == null);
    }

    private static boolean shippable(CustomerOrder o) {
        return o.isRequiresShipping() && (o.getStatus() == CustomerOrder.Status.PAID || o.getStatus() == CustomerOrder.Status.CONFIRMED);
    }

    private static String validateTrackingUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            URI uri = new URI(value);
            if (value.length() > 500 || !"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null) {
                throw new IllegalArgumentException();
            }
        } catch (Exception e) {
            throw ApiException.badRequest("TRACKING_URL_INVALID", "Tracking URL must be a valid http(s) address");
        }
        return value;
    }

    private CustomerOrder adminOrder(Storefront sf, Long id) {
        return orders.findByIdAndStorefront(id, sf).orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "Order not found"));
    }

    private CustomerOrder lockAdminOrder(Storefront sf, Long id) {
        return orders.lockById(id).filter(o -> o.getStorefront() == sf)
                .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "Order not found"));
    }

    private AdminOrderDetail adminView(CustomerOrder o) {
        var payment = payments.forOrder(o.getId()).orElse(null);
        long refunded = payment == null ? 0 : payment.refundedMinor();
        boolean paid = o.getStatus().isPaid();
        AdminActions actions = new AdminActions(cancellable(o), paid, paid ? o.getTotalMinor() - refunded : 0,
                shippable(o), o.getStatus() == CustomerOrder.Status.SHIPPED);
        return new AdminOrderDetail(o.getId(), o.getCustomerId(), o.getCustomerEmail(), o.getCustomerName(), detail(o), payment, actions);
    }

    private OrderDetail detail(CustomerOrder o) {
        var payment = payments.forOrder(o.getId());
        List<LineView> lines = o.getLines().stream().map(l -> new LineView(l.getId(), l.getProductId(), l.getTitle(),
                l.getVariantName(), l.getSku(), l.getProductType().name(), l.getUnitPriceMinor(), l.getQuantity(),
                l.getLineTotalMinor(), l.getFulfillmentStatus().name(), l.getHsnSac(), l.getGstRateBps(),
                l.getDiscountMinor(), l.getTaxableMinor(), l.getTaxMinor())).toList();
        List<ShipmentView> ships = shipments.findByOrderIdOrderByCreatedAtAsc(o.getId()).stream()
                .map(s -> new ShipmentView(s.getId(), s.getCarrier(), s.getTrackingNumber(), s.getTrackingUrl(),
                        s.getStatus().name(), s.getShippedAt(), s.getDeliveredAt())).toList();
        return new OrderDetail(o.getOrderNumber(), o.getStorefront().path(), o.getStatus().name(), o.getCurrency(),
                o.getSubtotalMinor(), o.getShippingMinor(), o.getTaxMinor(), o.getTotalMinor(), o.isRequiresShipping(),
                o.getShippingMethodName(), o.getShippingAddress(), o.getPlacedAt(), o.getExpiresAt(), o.getPaidAt(),
                o.getCancelledAt(), lines, ships, invoices.numberFor(o.getId()).orElse(null),
                payment.map(PaymentService.PaymentView::status).orElse(null),
                payment.map(PaymentService.PaymentView::failureReason).orElse(null), canPay(o),
                o.getPaymentMethod().name(), o.getDiscountMinor(), o.getCouponCode(), o.getCodFeeMinor(),
                o.getCgstMinor(), o.getSgstMinor(), o.getIgstMinor(), o.isPricesIncludeTax(), o.getBuyerStateCode(),
                o.getBuyerGstin());
    }
}
