package com.hybrid.platform.commerce.web;

import com.hybrid.platform.common.AppProperties;
import com.hybrid.platform.common.PageResponse;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.commerce.DashboardService;
import com.hybrid.platform.commerce.order.CustomerOrder;
import com.hybrid.platform.commerce.order.InvoiceService;
import com.hybrid.platform.commerce.order.OrderService;
import com.hybrid.platform.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/{storefront}/admin")
public class OrderAdminController {

    public record CancelRequest(@Size(max = 300) String reason) {
    }

    public record RefundRequest(Long amountMinor, boolean restock, @Size(max = 300) String reason) {
    }

    public record ShipRequest(@NotBlank @Size(max = 80) String carrier, @Size(max = 120) String trackingNumber,
                              @Size(max = 500) String trackingUrl) {
    }

    private final OrderService orders;
    private final DashboardService dashboard;
    private final AppProperties props;

    public OrderAdminController(OrderService orders, DashboardService dashboard, AppProperties props) {
        this.orders = orders;
        this.dashboard = dashboard;
        this.props = props;
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority('PERM_DASHBOARD_READ')")
    public DashboardService.Dashboard dashboard(@PathVariable String storefront) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return dashboard.get(sf, props.currency());
    }

    @GetMapping("/orders")
    @PreAuthorize("hasAuthority('PERM_ORDERS_READ')")
    public PageResponse<OrderService.AdminOrderSummary> list(@PathVariable String storefront,
                                                             @RequestParam(required = false) CustomerOrder.Status status,
                                                             @RequestParam(required = false) String q,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "25") int size) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return orders.adminList(sf, status, q, page, size);
    }

    @GetMapping("/orders/{id}")
    @PreAuthorize("hasAuthority('PERM_ORDERS_READ')")
    public OrderService.AdminOrderDetail detail(@PathVariable String storefront, @PathVariable Long id) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return orders.adminDetail(sf, id);
    }

    @GetMapping("/orders/{id}/invoice")
    @PreAuthorize("hasAuthority('PERM_ORDERS_READ')")
    public InvoiceService.InvoiceView invoice(@PathVariable String storefront, @PathVariable Long id) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return orders.adminInvoice(sf, id);
    }

    @PostMapping("/orders/{id}/cancel")
    @PreAuthorize("hasAuthority('PERM_ORDERS_WRITE')")
    public OrderService.AdminOrderDetail cancel(@PathVariable String storefront, @PathVariable Long id,
                                                @Valid @RequestBody(required = false) CancelRequest req) {
        return orders.cancel(CurrentUser.admin(Storefront.fromPath(storefront)), id, req == null ? null : req.reason());
    }

    @PostMapping("/orders/{id}/refund")
    @PreAuthorize("hasAuthority('PERM_REFUNDS')")
    public OrderService.AdminOrderDetail refund(@PathVariable String storefront, @PathVariable Long id,
                                                @Valid @RequestBody RefundRequest req) {
        return orders.refund(CurrentUser.admin(Storefront.fromPath(storefront)), id, req.amountMinor(), req.restock(), req.reason());
    }

    @PostMapping("/orders/{id}/shipments")
    @PreAuthorize("hasAuthority('PERM_ORDERS_WRITE')")
    public OrderService.AdminOrderDetail ship(@PathVariable String storefront, @PathVariable Long id,
                                              @Valid @RequestBody ShipRequest req) {
        return orders.ship(CurrentUser.admin(Storefront.fromPath(storefront)), id, req.carrier(), req.trackingNumber(),
                req.trackingUrl());
    }

    @PostMapping("/orders/{id}/delivered")
    @PreAuthorize("hasAuthority('PERM_ORDERS_WRITE')")
    public OrderService.AdminOrderDetail delivered(@PathVariable String storefront, @PathVariable Long id) {
        return orders.markDelivered(CurrentUser.admin(Storefront.fromPath(storefront)), id);
    }
}
