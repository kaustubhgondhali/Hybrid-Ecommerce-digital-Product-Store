package com.hybrid.platform.affiliate;

import com.hybrid.platform.common.Storefront;

public final class AffiliateEvents {

    private AffiliateEvents() {
    }

    /** An admin lowered a partner offer's display price by at least {@link AffiliateService#PRICE_DROP_MIN_PERCENT}%. */
    public record PriceDropped(Storefront storefront, Long productId, String productTitle, String merchantName,
                               long oldPriceMinor, long newPriceMinor) {
    }
}
