package com.hybrid.platform.wishlist;

import com.hybrid.platform.catalog.CatalogPublicService;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/{storefront}/wishlist")
public class WishlistController {

    private final WishlistService wishlist;

    public WishlistController(WishlistService wishlist) {
        this.wishlist = wishlist;
    }

    @GetMapping
    public List<CatalogPublicService.ProductCard> list(@PathVariable String storefront) {
        return wishlist.list(CurrentUser.customer().id(), Storefront.fromPath(storefront));
    }

    @GetMapping("/{slug}")
    public WishlistService.Status status(@PathVariable String storefront, @PathVariable String slug) {
        return wishlist.status(CurrentUser.customer().id(), Storefront.fromPath(storefront), slug);
    }

    @PutMapping("/{slug}")
    public WishlistService.Status add(@PathVariable String storefront, @PathVariable String slug) {
        return wishlist.add(CurrentUser.customer().id(), Storefront.fromPath(storefront), slug);
    }

    @DeleteMapping("/{slug}")
    public WishlistService.Status remove(@PathVariable String storefront, @PathVariable String slug) {
        return wishlist.remove(CurrentUser.customer().id(), Storefront.fromPath(storefront), slug);
    }
}
