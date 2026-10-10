package com.hybrid.platform.commerce.web;

import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.commerce.inventory.InventoryMovement;
import com.hybrid.platform.commerce.inventory.InventoryService;
import com.hybrid.platform.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/{storefront}/admin/inventory")
public class InventoryAdminController {

    public record AdjustRequest(int delta, InventoryMovement.Reason reason, @Size(max = 300) String note,
                                Integer lowStockThreshold) {
    }

    private final InventoryService inventory;

    public InventoryAdminController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_CATALOG_READ')")
    public List<InventoryService.StockView> list(@PathVariable String storefront) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return inventory.list(sf);
    }

    @GetMapping("/{variantId}/movements")
    @PreAuthorize("hasAuthority('PERM_CATALOG_READ')")
    public List<InventoryService.MovementView> movements(@PathVariable String storefront, @PathVariable Long variantId) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return inventory.movements(sf, variantId);
    }

    @PostMapping("/{variantId}/adjust")
    @PreAuthorize("hasAuthority('PERM_INVENTORY_WRITE')")
    public InventoryService.StockView adjust(@PathVariable String storefront, @PathVariable Long variantId,
                                             @Valid @RequestBody AdjustRequest req) {
        InventoryMovement.Reason reason = req.reason() == null ? InventoryMovement.Reason.ADJUSTMENT : req.reason();
        return inventory.adjust(CurrentUser.admin(Storefront.fromPath(storefront)), variantId, req.delta(), reason,
                req.note(), req.lowStockThreshold());
    }
}
