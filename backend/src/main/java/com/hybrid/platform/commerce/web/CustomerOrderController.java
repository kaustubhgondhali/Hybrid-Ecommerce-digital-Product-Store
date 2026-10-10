package com.hybrid.platform.commerce.web;

import com.hybrid.platform.common.PageResponse;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.commerce.coupon.CouponService;
import com.hybrid.platform.commerce.order.CheckoutService;
import com.hybrid.platform.commerce.order.InvoiceService;
import com.hybrid.platform.commerce.order.OrderService;
import com.hybrid.platform.security.CurrentUser;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class CustomerOrderController {

    public record CouponCheck(@NotNull String code) {
    }

    public record DemoOutcome(@NotNull String outcome) {
    }

    private final CheckoutService checkout;
    private final OrderService orders;

    public CustomerOrderController(CheckoutService checkout, OrderService orders) {
        this.checkout = checkout;
        this.orders = orders;
    }

    @PostMapping("/api/{storefront}/checkout/quote")
    public CheckoutService.Quote quote(@PathVariable String storefront,
                                       @RequestBody(required = false) CheckoutService.QuoteRequest req) {
        return checkout.quote(CurrentUser.customer(), Storefront.fromPath(storefront), req);
    }

    @PostMapping("/api/{storefront}/checkout/coupon")
    public CouponService.Applied checkCoupon(@PathVariable String storefront, @RequestBody CouponCheck req) {
        return checkout.checkCoupon(CurrentUser.customer(), Storefront.fromPath(storefront), req.code());
    }

    @PostMapping("/api/{storefront}/checkout/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public CheckoutService.PlacedOrder place(@PathVariable String storefront,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                             @RequestBody(required = false) CheckoutService.CheckoutRequest req) {
        return checkout.place(CurrentUser.customer(), Storefront.fromPath(storefront), req, idempotencyKey);
    }

    @GetMapping("/api/orders")
    public PageResponse<OrderService.OrderSummary> list(@RequestParam(required = false) String storefront,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        Storefront sf = storefront == null || storefront.isBlank() ? null : Storefront.fromPath(storefront);
        return orders.customerOrders(CurrentUser.customer(), sf, page, size);
    }

    @GetMapping("/api/orders/{orderNumber}")
    public OrderService.OrderDetail detail(@PathVariable String orderNumber) {
        return orders.customerOrder(CurrentUser.customer(), orderNumber);
    }

    @GetMapping("/api/orders/{orderNumber}/invoice")
    public InvoiceService.InvoiceView invoice(@PathVariable String orderNumber) {
        return orders.customerInvoice(CurrentUser.customer(), orderNumber);
    }

    @GetMapping("/api/orders/{orderNumber}/payment")
    public OrderService.PayInfo payment(@PathVariable String orderNumber) {
        return orders.payInfo(CurrentUser.customer(), orderNumber);
    }

    @PostMapping("/api/orders/{orderNumber}/payment/retry")
    public OrderService.PayInfo retry(@PathVariable String orderNumber) {
        return orders.retryPayment(CurrentUser.customer(), orderNumber);
    }

    /** Only exists while the demo payment provider is active; returns 404 otherwise. */
    @PostMapping("/api/payments/demo/{orderNumber}/complete")
    public OrderService.PayInfo demoComplete(@PathVariable String orderNumber, @RequestBody DemoOutcome req) {
        boolean success = "SUCCESS".equalsIgnoreCase(req.outcome());
        if (!success && !"FAILURE".equalsIgnoreCase(req.outcome())) {
            throw com.hybrid.platform.common.error.ApiException.badRequest("INVALID_OUTCOME", "Outcome must be SUCCESS or FAILURE");
        }
        return orders.completeDemoPayment(CurrentUser.customer(), orderNumber, success);
    }
}
