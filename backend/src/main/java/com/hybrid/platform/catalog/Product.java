package com.hybrid.platform.catalog;

import com.hybrid.platform.catalog.CatalogEnums.DeliveryKind;
import com.hybrid.platform.catalog.CatalogEnums.ProductStatus;
import com.hybrid.platform.catalog.CatalogEnums.ProductType;
import com.hybrid.platform.catalog.CatalogEnums.SellingMethod;
import com.hybrid.platform.common.Storefront;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Storefront storefront;

    @Enumerated(EnumType.STRING)
    @Column(name = "product_type", nullable = false)
    private ProductType productType;

    @Enumerated(EnumType.STRING)
    @Column(name = "selling_method", nullable = false)
    private SellingMethod sellingMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProductStatus status;

    @Column(nullable = false)
    private String slug;

    @Column(nullable = false)
    private String title;

    private String summary;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String brand;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "digital_format")
    private String digitalFormat;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_kind")
    private DeliveryKind deliveryKind;

    @Column(name = "type_locked", nullable = false)
    private boolean typeLocked;

    @Column(name = "meta_title")
    private String metaTitle;

    @Column(name = "meta_description")
    private String metaDescription;

    @Column(name = "focus_keyword")
    private String focusKeyword;

    @Column(nullable = false)
    private boolean noindex;

    @Column(name = "hsn_sac")
    private String hsnSac;

    /** GST rate in basis points (1800 = 18%); null means "use the store default rate". */
    @Column(name = "gst_rate_bps")
    private Integer gstRateBps;

    /** Deal content: one item per line. */
    @Column(columnDefinition = "TEXT")
    private String highlights;

    @Column(columnDefinition = "TEXT")
    private String pros;

    @Column(columnDefinition = "TEXT")
    private String cons;

    private String verdict;

    /** Specifications, one "Name: Value" per line. */
    @Column(columnDefinition = "TEXT")
    private String specs;

    /** Editor's rating in tenths of a star (45 = 4.5); null when not rated. */
    @Column(name = "expert_rating")
    private Integer expertRating;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    private List<ProductVariant> variants = new ArrayList<>();

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    private List<ProductImage> images = new ArrayList<>();

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    private List<ProductVideo> videos = new ArrayList<>();

    protected Product() {
    }

    public Product(Storefront storefront, ProductType productType, SellingMethod sellingMethod, String title, String slug) {
        this.storefront = storefront;
        this.productType = productType;
        this.sellingMethod = sellingMethod;
        this.title = title;
        this.slug = slug;
        this.status = ProductStatus.DRAFT;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /** Priced variants, paid for on this site (own stock or dropship). */
    public boolean isOnSite() {
        return sellingMethod.onSite();
    }

    /** Physical goods held in our own inventory. */
    public boolean isOwnStock() {
        return productType == ProductType.PHYSICAL && sellingMethod == SellingMethod.DIRECT;
    }

    public List<ProductVariant> activeVariants() {
        return variants.stream().filter(ProductVariant::isActive).toList();
    }

    public ProductVariant addVariant(ProductVariant v) {
        v.attach(this);
        variants.add(v);
        return v;
    }

    public ProductImage addImage(ProductImage img) {
        img.attach(this);
        images.add(img);
        return img;
    }

    public ProductVideo addVideo(ProductVideo video) {
        video.attach(this);
        videos.add(video);
        return video;
    }

    public Long getId() { return id; }
    public Storefront getStorefront() { return storefront; }
    public ProductType getProductType() { return productType; }
    public void setProductType(ProductType productType) { this.productType = productType; }
    public SellingMethod getSellingMethod() { return sellingMethod; }
    public void setSellingMethod(SellingMethod sellingMethod) { this.sellingMethod = sellingMethod; }
    public ProductStatus getStatus() { return status; }
    public void setStatus(ProductStatus status) { this.status = status; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public String getDigitalFormat() { return digitalFormat; }
    public void setDigitalFormat(String digitalFormat) { this.digitalFormat = digitalFormat; }
    public DeliveryKind getDeliveryKind() { return deliveryKind; }
    public void setDeliveryKind(DeliveryKind deliveryKind) { this.deliveryKind = deliveryKind; }
    public boolean isTypeLocked() { return typeLocked; }
    public void lockType() { this.typeLocked = true; }
    public String getMetaTitle() { return metaTitle; }
    public void setMetaTitle(String metaTitle) { this.metaTitle = metaTitle; }
    public String getMetaDescription() { return metaDescription; }
    public void setMetaDescription(String metaDescription) { this.metaDescription = metaDescription; }
    public String getFocusKeyword() { return focusKeyword; }
    public void setFocusKeyword(String focusKeyword) { this.focusKeyword = focusKeyword; }
    public boolean isNoindex() { return noindex; }
    public void setNoindex(boolean noindex) { this.noindex = noindex; }
    public String getHsnSac() { return hsnSac; }
    public void setHsnSac(String hsnSac) { this.hsnSac = hsnSac; }
    public Integer getGstRateBps() { return gstRateBps; }
    public void setGstRateBps(Integer gstRateBps) { this.gstRateBps = gstRateBps; }
    public String getHighlights() { return highlights; }
    public void setHighlights(String highlights) { this.highlights = highlights; }
    public String getPros() { return pros; }
    public void setPros(String pros) { this.pros = pros; }
    public String getCons() { return cons; }
    public void setCons(String cons) { this.cons = cons; }
    public String getVerdict() { return verdict; }
    public void setVerdict(String verdict) { this.verdict = verdict; }
    public String getSpecs() { return specs; }
    public void setSpecs(String specs) { this.specs = specs; }
    public Integer getExpertRating() { return expertRating; }
    public void setExpertRating(Integer expertRating) { this.expertRating = expertRating; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
    public List<ProductVariant> getVariants() { return variants; }
    public List<ProductImage> getImages() { return images; }
    public List<ProductVideo> getVideos() { return videos; }
}
