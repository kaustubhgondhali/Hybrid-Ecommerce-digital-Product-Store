package com.hybrid.platform.commerce.cart.web;

import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.commerce.cart.CartService;
import com.hybrid.platform.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/{storefront}/cart")
public class CartController {

    public record AddItemRequest(@NotNull Long variantId, Integer quantity) {
    }

    public record UpdateItemRequest(@NotNull Integer quantity) {
    }

    private final CartService service;

    public CartController(CartService service) {
        this.service = service;
    }

    @GetMapping
    public CartService.CartView get(@PathVariable String storefront) {
        return service.view(CurrentUser.customer(), Storefront.fromPath(storefront));
    }

    @PostMapping("/items")
    public CartService.CartView add(@PathVariable String storefront, @Valid @RequestBody AddItemRequest req) {
        return service.add(CurrentUser.customer(), Storefront.fromPath(storefront), req.variantId(),
                req.quantity() == null ? 1 : req.quantity());
    }

    @PatchMapping("/items/{itemId}")
    public CartService.CartView update(@PathVariable String storefront, @PathVariable Long itemId,
                                       @Valid @RequestBody UpdateItemRequest req) {
        return service.update(CurrentUser.customer(), Storefront.fromPath(storefront), itemId, req.quantity());
    }

    @DeleteMapping("/items/{itemId}")
    public CartService.CartView remove(@PathVariable String storefront, @PathVariable Long itemId) {
        return service.remove(CurrentUser.customer(), Storefront.fromPath(storefront), itemId);
    }
}
