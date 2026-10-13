package com.hybrid.platform.importer.web;

import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.importer.ProductImportService;
import com.hybrid.platform.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Admin: bulk import of affiliate products from a spreadsheet (dry run first, then import). */
@RestController
@RequestMapping("/api/{storefront}/admin/products/import")
public class ProductImportController {

    private final ProductImportService service;

    public ProductImportController(ProductImportService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_CATALOG_WRITE') and hasAuthority('PERM_AFFILIATE_WRITE')")
    public ProductImportService.ImportReport run(@PathVariable String storefront, @RequestBody ProductImportService.ImportRequest in) {
        return service.run(CurrentUser.admin(Storefront.fromPath(storefront)), in);
    }
}
