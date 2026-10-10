package com.hybrid.platform.commerce.settings;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "store_settings")
public class StoreSettings {

    @Id
    @Enumerated(EnumType.STRING)
    private Storefront storefront;

    @Column(name = "store_name", nullable = false)
    private String storeName;

    @Column(name = "support_email")
    private String supportEmail;

    @Column(name = "tax_rate_bps", nullable = false)
    private int taxRateBps;

    @Column(name = "seller_legal_name")
    private String sellerLegalName;

    @Column(name = "seller_address")
    private String sellerAddress;

    @Column(name = "seller_tax_id")
    private String sellerTaxId;

    @Column(name = "invoice_prefix", nullable = false)
    private String invoicePrefix;

    @Column(name = "seo_title")
    private String seoTitle;

    @Column(name = "seo_description")
    private String seoDescription;

    @Column(name = "google_verification")
    private String googleVerification;

    @Column(name = "bing_verification")
    private String bingVerification;

    @Column(name = "robots_extra", columnDefinition = "TEXT")
    private String robotsExtra;

    /** Two-digit GST state code of the seller (e.g. "27" Maharashtra). Decides CGST+SGST vs IGST. */
    @Column(name = "seller_state_code")
    private String sellerStateCode;

    @Column(name = "prices_include_tax", nullable = false)
    private boolean pricesIncludeTax = true;

    @Column(name = "default_hsn_sac")
    private String defaultHsnSac;

    @Column(name = "reviews_auto_approve", nullable = false)
    private boolean reviewsAutoApprove;

    @Column(name = "cod_enabled", nullable = false)
    private boolean codEnabled;

    @Column(name = "cod_max_minor")
    private Long codMaxMinor;

    @Column(name = "cod_fee_minor", nullable = false)
    private long codFeeMinor;

    @Column(name = "pincode_mode", nullable = false)
    private String pincodeMode = "ALL";

    @Column(name = "pincode_list", columnDefinition = "TEXT")
    private String pincodeList;

    @Column(name = "cod_pincode_list", columnDefinition = "TEXT")
    private String codPincodeList;

    @Column(name = "delivery_estimate")
    private String deliveryEstimate;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StoreSettings() {
    }

    public StoreSettings(Storefront storefront, String storeName, String invoicePrefix) {
        this.storefront = storefront;
        this.storeName = storeName;
        this.invoicePrefix = invoicePrefix;
        this.updatedAt = Instant.now();
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public Storefront getStorefront() { return storefront; }
    public String getStoreName() { return storeName; }
    public void setStoreName(String storeName) { this.storeName = storeName; }
    public String getSupportEmail() { return supportEmail; }
    public void setSupportEmail(String supportEmail) { this.supportEmail = supportEmail; }
    public int getTaxRateBps() { return taxRateBps; }
    public void setTaxRateBps(int taxRateBps) { this.taxRateBps = taxRateBps; }
    public String getSellerLegalName() { return sellerLegalName; }
    public void setSellerLegalName(String sellerLegalName) { this.sellerLegalName = sellerLegalName; }
    public String getSellerAddress() { return sellerAddress; }
    public void setSellerAddress(String sellerAddress) { this.sellerAddress = sellerAddress; }
    public String getSellerTaxId() { return sellerTaxId; }
    public void setSellerTaxId(String sellerTaxId) { this.sellerTaxId = sellerTaxId; }
    public String getInvoicePrefix() { return invoicePrefix; }
    public void setInvoicePrefix(String invoicePrefix) { this.invoicePrefix = invoicePrefix; }
    public String getSeoTitle() { return seoTitle; }
    public void setSeoTitle(String seoTitle) { this.seoTitle = seoTitle; }
    public String getSeoDescription() { return seoDescription; }
    public void setSeoDescription(String seoDescription) { this.seoDescription = seoDescription; }
    public String getGoogleVerification() { return googleVerification; }
    public void setGoogleVerification(String googleVerification) { this.googleVerification = googleVerification; }
    public String getBingVerification() { return bingVerification; }
    public void setBingVerification(String bingVerification) { this.bingVerification = bingVerification; }
    public String getRobotsExtra() { return robotsExtra; }
    public void setRobotsExtra(String robotsExtra) { this.robotsExtra = robotsExtra; }
    public String getSellerStateCode() { return sellerStateCode; }
    public void setSellerStateCode(String sellerStateCode) { this.sellerStateCode = sellerStateCode; }
    public boolean isPricesIncludeTax() { return pricesIncludeTax; }
    public void setPricesIncludeTax(boolean pricesIncludeTax) { this.pricesIncludeTax = pricesIncludeTax; }
    public String getDefaultHsnSac() { return defaultHsnSac; }
    public void setDefaultHsnSac(String defaultHsnSac) { this.defaultHsnSac = defaultHsnSac; }
    public boolean isReviewsAutoApprove() { return reviewsAutoApprove; }
    public void setReviewsAutoApprove(boolean reviewsAutoApprove) { this.reviewsAutoApprove = reviewsAutoApprove; }
    public boolean isCodEnabled() { return codEnabled; }
    public void setCodEnabled(boolean codEnabled) { this.codEnabled = codEnabled; }
    public Long getCodMaxMinor() { return codMaxMinor; }
    public void setCodMaxMinor(Long codMaxMinor) { this.codMaxMinor = codMaxMinor; }
    public long getCodFeeMinor() { return codFeeMinor; }
    public void setCodFeeMinor(long codFeeMinor) { this.codFeeMinor = codFeeMinor; }
    public String getPincodeMode() { return pincodeMode; }
    public void setPincodeMode(String pincodeMode) { this.pincodeMode = pincodeMode; }
    public String getPincodeList() { return pincodeList; }
    public void setPincodeList(String pincodeList) { this.pincodeList = pincodeList; }
    public String getCodPincodeList() { return codPincodeList; }
    public void setCodPincodeList(String codPincodeList) { this.codPincodeList = codPincodeList; }
    public String getDeliveryEstimate() { return deliveryEstimate; }
    public void setDeliveryEstimate(String deliveryEstimate) { this.deliveryEstimate = deliveryEstimate; }
    public Instant getUpdatedAt() { return updatedAt; }
}
