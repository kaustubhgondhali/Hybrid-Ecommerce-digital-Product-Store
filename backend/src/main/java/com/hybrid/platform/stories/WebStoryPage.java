package com.hybrid.platform.stories;

import jakarta.persistence.*;

/** One full-screen page of a Web Story: a photo or short video, optional text and an optional product link. */
@Entity
@Table(name = "web_story_pages")
public class WebStoryPage {

    public enum MediaType { IMAGE, VIDEO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "story_id")
    private WebStory story;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false, updatable = false)
    private MediaType mediaType;

    @Column(name = "media_key", nullable = false, updatable = false)
    private String mediaKey;

    @Column(name = "poster_key", updatable = false)
    private String posterKey;

    @Column(updatable = false)
    private Integer width;

    @Column(updatable = false)
    private Integer height;

    @Column(name = "alt_text")
    private String altText;

    private String headline;

    private String body;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "cta_label")
    private String ctaLabel;

    protected WebStoryPage() {
    }

    public WebStoryPage(MediaType mediaType, String mediaKey, String posterKey, Integer width, Integer height) {
        this.mediaType = mediaType;
        this.mediaKey = mediaKey;
        this.posterKey = posterKey;
        this.width = width;
        this.height = height;
    }

    void attach(WebStory story) {
        this.story = story;
    }

    public String mediaUrl() {
        return "/media/" + mediaKey;
    }

    public String posterUrl() {
        return posterKey == null ? null : "/media/" + posterKey;
    }

    public boolean isVideo() {
        return mediaType == MediaType.VIDEO;
    }

    /** Photos clearly wider than a phone screen are shown whole on a blurred copy instead of being cropped. */
    public boolean showWhole() {
        return mediaType == MediaType.IMAGE && width != null && height != null && height > 0 && width / (double) height > 0.7;
    }

    public String videoType() {
        return mediaKey.endsWith(".webm") ? "video/webm" : "video/mp4";
    }

    public Long getId() { return id; }
    public WebStory getStory() { return story; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public MediaType getMediaType() { return mediaType; }
    public String getMediaKey() { return mediaKey; }
    public String getPosterKey() { return posterKey; }
    public Integer getWidth() { return width; }
    public Integer getHeight() { return height; }
    public String getAltText() { return altText; }
    public void setAltText(String altText) { this.altText = altText; }
    public String getHeadline() { return headline; }
    public void setHeadline(String headline) { this.headline = headline; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getCtaLabel() { return ctaLabel; }
    public void setCtaLabel(String ctaLabel) { this.ctaLabel = ctaLabel; }
}
