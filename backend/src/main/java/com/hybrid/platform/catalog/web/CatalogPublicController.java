package com.hybrid.platform.catalog.web;

import com.hybrid.platform.catalog.CatalogEnums.ProductType;
import com.hybrid.platform.catalog.CatalogEnums.SellingMethod;
import com.hybrid.platform.catalog.CatalogPublicService;
import com.hybrid.platform.common.PageResponse;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.insights.DemandTracker;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/{storefront}/catalog")
public class CatalogPublicController {

    private final CatalogPublicService service;
    private final DemandTracker demand;

    public CatalogPublicController(CatalogPublicService service, DemandTracker demand) {
        this.service = service;
        this.demand = demand;
    }

    @GetMapping("/products")
    public PageResponse<CatalogPublicService.ProductCard> products(@PathVariable String storefront,
                                                                   @RequestParam(required = false) String q,
                                                                   @RequestParam(required = false) String category,
                                                                   @RequestParam(required = false) ProductType type,
                                                                   @RequestParam(required = false) SellingMethod method,
                                                                   @RequestParam(required = false) String sort,
                                                                   @RequestParam(required = false) String store,
                                                                   @RequestParam(required = false) Integer minDiscount,
                                                                   @RequestParam(required = false) Long minPrice,
                                                                   @RequestParam(required = false) Long maxPrice,
                                                                   @RequestParam(defaultValue = "0") int page,
                                                                   @RequestParam(defaultValue = "24") int size,
                                                                   @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        Storefront sf = Storefront.fromPath(storefront);
        PageResponse<CatalogPublicService.ProductCard> result = service.browse(sf, q, category, type, method, sort, store, minDiscount,
                minPrice == null ? null : minPrice * 100, maxPrice == null ? null : maxPrice * 100, page, size);
        if (q != null && page == 0) demand.searched(sf, q, result.totalItems(), userAgent);
        return result;
    }

    /** Sent once per visit by the product page; feeds the Sales & demand report. */
    @PostMapping("/products/{slug}/view")
    public ResponseEntity<Void> viewed(@PathVariable String storefront, @PathVariable String slug,
                                       @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        demand.productViewed(Storefront.fromPath(storefront), slug, userAgent);
        return ResponseEntity.noContent().build();
    }

    /** As-you-type search suggestions. */
    @GetMapping("/suggest")
    public CatalogPublicService.Suggestions suggest(@PathVariable String storefront, @RequestParam(required = false) String q) {
        return service.suggest(Storefront.fromPath(storefront), q);
    }

    /** Cards for given product ids (recently viewed, compare tray); unknown or hidden products are left out. */
    @GetMapping("/cards")
    public List<CatalogPublicService.ProductCard> cards(@PathVariable String storefront, @RequestParam List<Long> ids) {
        return service.cards(Storefront.fromPath(storefront), ids.stream().distinct().limit(24).toList());
    }

    /** Partner stores with live deals, most deals first. */
    @GetMapping("/stores")
    public List<CatalogPublicService.StoreView> stores(@PathVariable String storefront) {
        return service.stores(Storefront.fromPath(storefront));
    }

    @GetMapping("/products/{slug}")
    public CatalogPublicService.ProductDetail product(@PathVariable String storefront, @PathVariable String slug) {
        return service.detail(Storefront.fromPath(storefront), slug);
    }

    @GetMapping("/categories")
    public List<CatalogPublicService.CategoryView> categories(@PathVariable String storefront) {
        return service.categories(Storefront.fromPath(storefront));
    }

    /** Home-page sections: featured, top deals, price drops, trending and categories. */
    @GetMapping("/home")
    public CatalogPublicService.Home home(@PathVariable String storefront) {
        return service.home(Storefront.fromPath(storefront));
    }
}
