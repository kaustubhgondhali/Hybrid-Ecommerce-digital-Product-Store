package com.hybrid.platform.review.web;

import com.hybrid.platform.common.PageResponse;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.review.ReviewService;
import com.hybrid.platform.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
public class ReviewController {

    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    @GetMapping("/api/{storefront}/catalog/products/{slug}/reviews")
    public ReviewService.ProductReviews list(@PathVariable String storefront, @PathVariable String slug,
                                             @RequestParam(defaultValue = "0") int page) {
        return service.forProduct(Storefront.fromPath(storefront), slug, page, CurrentUser.optionalCustomer());
    }

    @PostMapping("/api/{storefront}/catalog/products/{slug}/reviews")
    public ReviewService.MyReview submit(@PathVariable String storefront, @PathVariable String slug,
                                         @RequestBody ReviewService.ReviewInput input) {
        return service.submit(CurrentUser.customer(), Storefront.fromPath(storefront), slug, input);
    }

    @GetMapping("/api/{storefront}/admin/reviews")
    @PreAuthorize("hasAuthority('PERM_CATALOG_READ')")
    public PageResponse<ReviewService.AdminReview> adminList(@PathVariable String storefront,
                                                             @RequestParam(required = false) String status,
                                                             @RequestParam(defaultValue = "0") int page) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return service.adminList(sf, status, page);
    }

    @PatchMapping("/api/{storefront}/admin/reviews/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_CATALOG_WRITE')")
    public void moderate(@PathVariable String storefront, @PathVariable Long id,
                         @RequestBody ReviewService.ModerationInput input) {
        service.moderate(CurrentUser.admin(Storefront.fromPath(storefront)), id, input);
    }

    @DeleteMapping("/api/{storefront}/admin/reviews/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_CATALOG_WRITE')")
    public void delete(@PathVariable String storefront, @PathVariable Long id) {
        service.delete(CurrentUser.admin(Storefront.fromPath(storefront)), id);
    }
}
