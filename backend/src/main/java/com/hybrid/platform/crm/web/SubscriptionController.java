package com.hybrid.platform.crm.web;

import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.crm.SubscriptionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Visitor sign-ups (price alerts, newsletter) and the confirm / stop links from their emails. */
@RestController
public class SubscriptionController {

    private final SubscriptionService service;

    public SubscriptionController(SubscriptionService service) {
        this.service = service;
    }

    @PostMapping("/api/{storefront}/price-alerts")
    public SubscriptionService.SignupResult alert(@PathVariable String storefront, @RequestBody SubscriptionService.AlertInput input) {
        return service.requestAlert(Storefront.fromPath(storefront), input);
    }

    @PostMapping("/api/{storefront}/newsletter")
    public SubscriptionService.SignupResult subscribe(@PathVariable String storefront, @RequestBody SubscriptionService.SignupInput input) {
        return service.subscribe(Storefront.fromPath(storefront), input);
    }

    @GetMapping("/api/price-alerts/confirm")
    public ResponseEntity<Void> confirmAlert(@RequestParam(name = "t", required = false) String token) {
        return toProduct(service.confirmAlert(token), "alert=on");
    }

    @GetMapping("/api/price-alerts/cancel")
    public ResponseEntity<Void> cancelAlert(@RequestParam(name = "t", required = false) String token) {
        return toProduct(service.cancelAlert(token), "alert=off");
    }

    @GetMapping("/api/newsletter/confirm")
    public ResponseEntity<Void> confirm(@RequestParam(name = "t", required = false) String token) {
        SubscriptionService.LinkResult r = service.confirmSubscription(token);
        return redirect(r.ok() ? "/" + r.storefront().path() + "?newsletter=on" : "/link-unavailable.html");
    }

    @GetMapping("/api/newsletter/unsubscribe")
    public ResponseEntity<Void> unsubscribe(@RequestParam(name = "t", required = false) String token) {
        SubscriptionService.LinkResult r = service.unsubscribe(token);
        return redirect(r.ok() ? "/" + r.storefront().path() + "?newsletter=off" : "/link-unavailable.html");
    }

    private static ResponseEntity<Void> toProduct(SubscriptionService.LinkResult r, String flag) {
        if (!r.ok()) return redirect("/link-unavailable.html");
        String base = "/" + r.storefront().path();
        return redirect(r.slug() == null ? base + "?" + flag : base + "/p/" + URLEncoder.encode(r.slug(), StandardCharsets.UTF_8) + "?" + flag);
    }

    private static ResponseEntity<Void> redirect(String path) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(path)).header("Cache-Control", "no-store").build();
    }
}
