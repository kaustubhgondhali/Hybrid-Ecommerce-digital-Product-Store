package com.hybrid.platform.commerce.order;

import com.hybrid.platform.catalog.CatalogEnums.ProductType;
import com.hybrid.platform.catalog.CatalogEnums.SellingMethod;
import jakarta.persistence.*;

@Entity
@Table(name = "order_lines")
public class OrderLine {

    public enum Fulfillment { UNFULFILLED, DELIVERED_DIGITAL, SHIPPED, DELIVERED, CANCELLED, REFUNDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private CustomerOrder order;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "variant_id", nullable = false, updatable = false)
    private Long variantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "product_type", nullable = false, updatable = false)
    private ProductType productType;

    @Enumerated(EnumType.STRING)
    @Column(name = "selling_method", nullable = false, updatable = false)
    private SellingMethod sellingMethod;

    @Column(updatable = false)
    private String sku;

    @Column(nullable = false, updatable = false)
    private String title;

    @Column(name = "variant_name", nullable = false, updatable = false)
    private String variantName;

    @Column(name = "unit_price_minor", nullable = false, updatable = false)
    private long unitPriceMinor;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(name = "line_total_minor", nullable = false, updatable = false)
    private long lineTotalMinor;

    @Enumerated(EnumType.STRING)
    @Column(name = "fulfillment_status", nullable = false)
    private Fulfillment fulfillmentStatus;

    @Column(name = "hsn_sac", updatable = false)
    private String hsnSac;

    @Column(name = "gst_rate_bps", nullable = false)
    private int gstRateBps;

    @Column(name = "discount_minor", nullable = false)
    private long discountMinor;

    @Column(name = "taxable_minor", nullable = false)
    private long taxableMinor;

    @Column(name = "tax_minor", nullable = false)
    private long taxMinor;

    protected OrderLine() {
    }

    public OrderLine(Long productId, Long variantId, ProductType productType, SellingMethod sellingMethod, String sku,
                     String title, String variantName, long unitPriceMinor, int quantity) {
        if (!sellingMethod.onSite()) throw new IllegalArgumentException("Partner offers are not ordered here");
        this.productId = productId;
        this.variantId = variantId;
        this.productType = productType;
        this.sellingMethod = sellingMethod;
        this.sku = sku;
        this.title = title;
        this.variantName = variantName;
        this.unitPriceMinor = unitPriceMinor;
        this.quantity = quantity;
        this.lineTotalMinor = unitPriceMinor * quantity;
        this.fulfillmentStatus = Fulfillment.UNFULFILLED;
    }

    void attach(CustomerOrder order) {
        this.order = order;
    }

    public boolean isPhysical() {
        return productType == ProductType.PHYSICAL;
    }

    /** Taken from our own inventory (dropship lines are fulfilled from the supplier's stock). */
    public boolean isOwnStock() {
        return isPhysical() && sellingMethod == SellingMethod.DIRECT;
    }

    public Long getId() { return id; }
    public Long getProductId() { return productId; }
    public Long getVariantId() { return variantId; }
    public ProductType getProductType() { return productType; }
    public String getSku() { return sku; }
    public String getTitle() { return title; }
    public String getVariantName() { return variantName; }
    public long getUnitPriceMinor() { return unitPriceMinor; }
    public int getQuantity() { return quantity; }
    public long getLineTotalMinor() { return lineTotalMinor; }
    public void setTax(String hsnSac, GstCalculator.LineOut gst) {
        this.hsnSac = hsnSac;
        this.gstRateBps = gst.rateBps();
        this.discountMinor = gst.discountMinor();
        this.taxableMinor = gst.taxableMinor();
        this.taxMinor = gst.taxMinor();
    }

    public String getHsnSac() { return hsnSac; }
    public int getGstRateBps() { return gstRateBps; }
    public long getDiscountMinor() { return discountMinor; }
    public long getTaxableMinor() { return taxableMinor; }
    public long getTaxMinor() { return taxMinor; }
    public Fulfillment getFulfillmentStatus() { return fulfillmentStatus; }
    public void setFulfillmentStatus(Fulfillment fulfillmentStatus) { this.fulfillmentStatus = fulfillmentStatus; }
}
