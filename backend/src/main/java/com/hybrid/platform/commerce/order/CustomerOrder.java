package com.hybrid.platform.commerce.order;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
public class CustomerOrder {

    public enum PaymentMethod { ONLINE, COD }

    /** CONFIRMED = cash-on-delivery order accepted and awaiting dispatch; money is collected on delivery. */
    public enum Status {
        PENDING_PAYMENT, CONFIRMED, PAID, SHIPPED, DELIVERED, COMPLETED, NEEDS_ATTENTION,
        CANCELLED, EXPIRED, PARTIALLY_REFUNDED, REFUNDED;

        public boolean isPaid() {
            return this == PAID || this == SHIPPED || this == DELIVERED || this == COMPLETED
                    || this == NEEDS_ATTENTION || this == PARTIALLY_REFUNDED;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_number", nullable = false, updatable = false)
    private String orderNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Storefront storefront;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "customer_email", nullable = false)
    private String customerEmail;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(name = "subtotal_minor", nullable = false)
    private long subtotalMinor;

    @Column(name = "shipping_minor", nullable = false)
    private long shippingMinor;

    @Column(name = "tax_minor", nullable = false)
    private long taxMinor;

    @Column(name = "total_minor", nullable = false)
    private long totalMinor;

    @Column(name = "requires_shipping", nullable = false)
    private boolean requiresShipping;

    @Column(name = "shipping_method_name")
    private String shippingMethodName;

    @Embedded
    private ShippingAddress shippingAddress;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Column(name = "placed_at", nullable = false, updatable = false)
    private Instant placedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "discount_minor", nullable = false)
    private long discountMinor;

    @Column(name = "coupon_code")
    private String couponCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false)
    private PaymentMethod paymentMethod = PaymentMethod.ONLINE;

    @Column(name = "cod_fee_minor", nullable = false)
    private long codFeeMinor;

    @Column(name = "buyer_state_code")
    private String buyerStateCode;

    @Column(name = "buyer_gstin")
    private String buyerGstin;

    @Column(name = "prices_include_tax", nullable = false)
    private boolean pricesIncludeTax;

    @Column(name = "cgst_minor", nullable = false)
    private long cgstMinor;

    @Column(name = "sgst_minor", nullable = false)
    private long sgstMinor;

    @Column(name = "igst_minor", nullable = false)
    private long igstMinor;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<OrderLine> lines = new ArrayList<>();

    @Version
    private long version;

    protected CustomerOrder() {
    }

    public CustomerOrder(String orderNumber, Storefront storefront, Long customerId, String customerEmail,
                         String customerName, String currency, String idempotencyKey, Instant expiresAt) {
        this.orderNumber = orderNumber;
        this.storefront = storefront;
        this.customerId = customerId;
        this.customerEmail = customerEmail;
        this.customerName = customerName;
        this.currency = currency;
        this.idempotencyKey = idempotencyKey;
        this.status = Status.PENDING_PAYMENT;
        this.placedAt = Instant.now();
        this.expiresAt = expiresAt;
    }

    public OrderLine addLine(OrderLine line) {
        line.attach(this);
        lines.add(line);
        return line;
    }

    public void setAmounts(GstCalculator.Result gst, long shipping, long codFee, boolean pricesIncludeTax) {
        this.subtotalMinor = gst.subtotalMinor();
        this.discountMinor = gst.discountMinor();
        this.shippingMinor = shipping;
        this.codFeeMinor = codFee;
        this.taxMinor = gst.taxMinor();
        this.cgstMinor = gst.cgstMinor();
        this.sgstMinor = gst.sgstMinor();
        this.igstMinor = gst.igstMinor();
        this.pricesIncludeTax = pricesIncludeTax;
        this.totalMinor = gst.totalMinor();
    }

    public boolean isCod() {
        return paymentMethod == PaymentMethod.COD;
    }

    public List<OrderLine> physicalLines() {
        return lines.stream().filter(OrderLine::isPhysical).toList();
    }

    /** Lines reserved from, and restocked to, our own inventory. */
    public List<OrderLine> stockLines() {
        return lines.stream().filter(OrderLine::isOwnStock).toList();
    }

    public List<OrderLine> digitalLines() {
        return lines.stream().filter(l -> !l.isPhysical()).toList();
    }

    public Long getId() { return id; }
    public String getOrderNumber() { return orderNumber; }
    public Storefront getStorefront() { return storefront; }
    public Long getCustomerId() { return customerId; }
    public String getCustomerEmail() { return customerEmail; }
    public String getCustomerName() { return customerName; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getCurrency() { return currency; }
    public long getSubtotalMinor() { return subtotalMinor; }
    public long getShippingMinor() { return shippingMinor; }
    public long getTaxMinor() { return taxMinor; }
    public long getTotalMinor() { return totalMinor; }
    public boolean isRequiresShipping() { return requiresShipping; }
    public void setRequiresShipping(boolean requiresShipping) { this.requiresShipping = requiresShipping; }
    public String getShippingMethodName() { return shippingMethodName; }
    public void setShippingMethodName(String shippingMethodName) { this.shippingMethodName = shippingMethodName; }
    public ShippingAddress getShippingAddress() { return shippingAddress; }
    public void setShippingAddress(ShippingAddress shippingAddress) { this.shippingAddress = shippingAddress; }
    public Instant getPlacedAt() { return placedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }
    public List<OrderLine> getLines() { return lines; }
    public long getDiscountMinor() { return discountMinor; }
    public String getCouponCode() { return couponCode; }
    public void setCouponCode(String couponCode) { this.couponCode = couponCode; }
    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }
    public long getCodFeeMinor() { return codFeeMinor; }
    public String getBuyerStateCode() { return buyerStateCode; }
    public void setBuyerStateCode(String buyerStateCode) { this.buyerStateCode = buyerStateCode; }
    public String getBuyerGstin() { return buyerGstin; }
    public void setBuyerGstin(String buyerGstin) { this.buyerGstin = buyerGstin; }
    public boolean isPricesIncludeTax() { return pricesIncludeTax; }
    public long getCgstMinor() { return cgstMinor; }
    public long getSgstMinor() { return sgstMinor; }
    public long getIgstMinor() { return igstMinor; }
}
