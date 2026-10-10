package com.hybrid.platform.catalog.web;

import com.hybrid.platform.catalog.CatalogAdminService;
import com.hybrid.platform.catalog.CatalogAdminService.ProductInput;
import com.hybrid.platform.catalog.CatalogEnums.DeliveryKind;
import com.hybrid.platform.catalog.CatalogEnums.ProductStatus;
import com.hybrid.platform.catalog.CatalogEnums.ProductType;
import com.hybrid.platform.catalog.CatalogEnums.SellingMethod;
import com.hybrid.platform.common.PageResponse;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/{storefront}/admin")
public class CatalogAdminController {

    public record ProductRequest(ProductType productType, SellingMethod sellingMethod,
                                 @Size(max = 200) String title, @Size(max = 160) String slug,
                                 @Size(max = 500) String summary, @Size(max = 20000) String description,
                                 @Size(max = 120) String brand, Long categoryId,
                                 @Size(max = 60) String digitalFormat, DeliveryKind deliveryKind,
                                 Long priceMinor, Long compareAtMinor, @Size(max = 80) String sku, Integer weightGrams,
                                 @Size(max = 200) String metaTitle, @Size(max = 320) String metaDescription,
                                 @Size(max = 100) String focusKeyword, Boolean noindex,
                                 @Size(max = 8) String hsnSac, Integer gstRateBps, Long version) {
        ProductInput toInput() {
            return new ProductInput(productType, sellingMethod, title, slug, summary, description, brand, categoryId,
                    digitalFormat, deliveryKind, priceMinor, compareAtMinor, sku, weightGrams,
                    metaTitle, metaDescription, focusKeyword, noindex, hsnSac, gstRateBps);
        }
    }

    public record StatusRequest(@NotNull ProductStatus status) {
    }

    public record VariantRequest(@Size(max = 120) String name, @Size(max = 80) String sku, Long priceMinor,
                                 Long compareAtMinor, Integer weightGrams, Boolean active, Integer sortOrder) {
        CatalogAdminService.VariantInput toInput() {
            return new CatalogAdminService.VariantInput(name, sku, priceMinor, compareAtMinor, weightGrams, active, sortOrder);
        }
    }

    public record CategoryRequest(@Size(max = 120) String name, @Size(max = 140) String slug, Integer sortOrder, Boolean active,
                                  @Size(max = 5000) String description, @Size(max = 200) String metaTitle,
                                  @Size(max = 320) String metaDescription) {
        CatalogAdminService.CategoryInput toInput() {
            return new CatalogAdminService.CategoryInput(name, slug, sortOrder, active, description, metaTitle, metaDescription);
        }
    }

    private final CatalogAdminService service;

    public CatalogAdminController(CatalogAdminService service) {
        this.service = service;
    }

    @GetMapping("/products")
    @PreAuthorize("hasAuthority('PERM_CATALOG_READ')")
    public PageResponse<CatalogAdminService.AdminProductSummary> list(@PathVariable String storefront,
                                                                      @RequestParam(required = false) String q,
                                                                      @RequestParam(required = false) ProductStatus status,
                                                                      @RequestParam(required = false) ProductType type,
                                                                      @RequestParam(required = false) SellingMethod method,
                                                                      @RequestParam(defaultValue = "0") int page,
                                                                      @RequestParam(defaultValue = "25") int size) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return service.list(sf, q, status, type, method, page, size);
    }

    @GetMapping("/products/{id}")
    @PreAuthorize("hasAuthority('PERM_CATALOG_READ')")
    public CatalogAdminService.AdminProductView get(@PathVariable String storefront, @PathVariable Long id) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return service.get(sf, id);
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_CATALOG_WRITE')")
    public CatalogAdminService.AdminProductView create(@PathVariable String storefront, @Valid @RequestBody ProductRequest req) {
        return service.create(CurrentUser.admin(Storefront.fromPath(storefront)), req.toInput());
    }

    @PutMapping("/products/{id}")
    @PreAuthorize("hasAuthority('PERM_CATALOG_WRITE')")
    public CatalogAdminService.AdminProductView update(@PathVariable String storefront, @PathVariable Long id,
                                                       @Valid @RequestBody ProductRequest req) {
        return service.update(CurrentUser.admin(Storefront.fromPath(storefront)), id, req.toInput(), req.version());
    }

    @PostMapping("/products/{id}/status")
    @PreAuthorize("hasAuthority('PERM_CATALOG_WRITE')")
    public CatalogAdminService.AdminProductView status(@PathVariable String storefront, @PathVariable Long id,
                                                       @Valid @RequestBody StatusRequest req) {
        return service.changeStatus(CurrentUser.admin(Storefront.fromPath(storefront)), id, req.status());
    }

    @PostMapping("/products/{id}/variants")
    @PreAuthorize("hasAuthority('PERM_CATALOG_WRITE')")
    public CatalogAdminService.AdminProductView addVariant(@PathVariable String storefront, @PathVariable Long id,
                                                           @Valid @RequestBody VariantRequest req) {
        return service.addVariant(CurrentUser.admin(Storefront.fromPath(storefront)), id, req.toInput());
    }

    @PutMapping("/products/{id}/variants/{variantId}")
    @PreAuthorize("hasAuthority('PERM_CATALOG_WRITE')")
    public CatalogAdminService.AdminProductView updateVariant(@PathVariable String storefront, @PathVariable Long id,
                                                              @PathVariable Long variantId, @Valid @RequestBody VariantRequest req) {
        return service.updateVariant(CurrentUser.admin(Storefront.fromPath(storefront)), id, variantId, req.toInput());
    }

    @PostMapping(value = "/products/{id}/images", consumes = "multipart/form-data")
    @PreAuthorize("hasAuthority('PERM_CATALOG_WRITE')")
    public CatalogAdminService.AdminProductView uploadImage(@PathVariable String storefront, @PathVariable Long id,
                                                            @RequestParam("file") MultipartFile file,
                                                            @RequestParam(value = "alt", required = false) String alt) throws IOException {
        return service.uploadImage(CurrentUser.admin(Storefront.fromPath(storefront)), id, file, alt);
    }

    public record ImageAltRequest(@Size(max = 250) String alt) {
    }

    @PatchMapping("/products/{id}/images/{imageId}")
    @PreAuthorize("hasAuthority('PERM_CATALOG_WRITE')")
    public CatalogAdminService.AdminProductView updateImageAlt(@PathVariable String storefront, @PathVariable Long id,
                                                               @PathVariable Long imageId, @Valid @RequestBody ImageAltRequest req) {
        return service.updateImageAlt(CurrentUser.admin(Storefront.fromPath(storefront)), id, imageId, req.alt());
    }

    @DeleteMapping("/products/{id}/images/{imageId}")
    @PreAuthorize("hasAuthority('PERM_CATALOG_WRITE')")
    public CatalogAdminService.AdminProductView deleteImage(@PathVariable String storefront, @PathVariable Long id,
                                                            @PathVariable Long imageId) {
        return service.deleteImage(CurrentUser.admin(Storefront.fromPath(storefront)), id, imageId);
    }

    @GetMapping("/categories")
    @PreAuthorize("hasAuthority('PERM_CATALOG_READ')")
    public List<CatalogAdminService.AdminCategoryView> categories(@PathVariable String storefront) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return service.listCategories(sf);
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_CATALOG_WRITE')")
    public CatalogAdminService.AdminCategoryView createCategory(@PathVariable String storefront, @Valid @RequestBody CategoryRequest req) {
        return service.createCategory(CurrentUser.admin(Storefront.fromPath(storefront)), req.toInput());
    }

    @PutMapping("/categories/{id}")
    @PreAuthorize("hasAuthority('PERM_CATALOG_WRITE')")
    public CatalogAdminService.AdminCategoryView updateCategory(@PathVariable String storefront, @PathVariable Long id,
                                                                @Valid @RequestBody CategoryRequest req) {
        return service.updateCategory(CurrentUser.admin(Storefront.fromPath(storefront)), id, req.toInput());
    }

    @DeleteMapping("/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_CATALOG_WRITE')")
    public void deleteCategory(@PathVariable String storefront, @PathVariable Long id) {
        service.deleteCategory(CurrentUser.admin(Storefront.fromPath(storefront)), id);
    }
}
