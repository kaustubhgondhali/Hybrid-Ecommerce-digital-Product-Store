package com.hybrid.platform.catalog.web;

import com.hybrid.platform.catalog.CatalogEnums.ProductType;
import com.hybrid.platform.catalog.CatalogEnums.SellingMethod;
import com.hybrid.platform.catalog.CatalogPublicService;
import com.hybrid.platform.common.PageResponse;
import com.hybrid.platform.common.Storefront;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/{storefront}/catalog")
public class CatalogPublicController {

    private final CatalogPublicService service;

    public CatalogPublicController(CatalogPublicService service) {
        this.service = service;
    }

    @GetMapping("/products")
    public PageResponse<CatalogPublicService.ProductCard> products(@PathVariable String storefront,
                                                                   @RequestParam(required = false) String q,
                                                                   @RequestParam(required = false) String category,
                                                                   @RequestParam(required = false) ProductType type,
                                                                   @RequestParam(required = false) SellingMethod method,
                                                                   @RequestParam(required = false) String sort,
                                                                   @RequestParam(defaultValue = "0") int page,
                                                                   @RequestParam(defaultValue = "24") int size) {
        return service.list(Storefront.fromPath(storefront), q, category, type, method, sort, page, size);
    }

    @GetMapping("/products/{slug}")
    public CatalogPublicService.ProductDetail product(@PathVariable String storefront, @PathVariable String slug) {
        return service.detail(Storefront.fromPath(storefront), slug);
    }

    @GetMapping("/categories")
    public List<CatalogPublicService.CategoryView> categories(@PathVariable String storefront) {
        return service.categories(Storefront.fromPath(storefront));
    }
}
