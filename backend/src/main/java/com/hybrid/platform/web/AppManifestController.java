package com.hybrid.platform.web;

import com.hybrid.platform.common.Storefront;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Web app manifest per storefront, so shoppers can install Elaashop or Elaatech on their phone's home screen. */
@RestController
public class AppManifestController {

    private static final MediaType MANIFEST = MediaType.parseMediaType("application/manifest+json;charset=UTF-8");

    @GetMapping("/{sf:shop|digital}/manifest.webmanifest")
    public ResponseEntity<Map<String, Object>> manifest(@PathVariable String sf) {
        Storefront s = Storefront.fromPath(sf);
        String brand = s == Storefront.SHOP ? "elaashop" : "elaatech";
        String name = s == Storefront.SHOP ? "Elaashop" : "Elaatech";
        String base = "/" + s.path();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", base);
        m.put("name", name);
        m.put("short_name", name);
        m.put("description", s == Storefront.SHOP
                ? "Hand-picked deals on audio, tech and lifestyle from trusted stores in India."
                : "Hand-picked deals on software, courses and e-books.");
        m.put("start_url", base + "?source=app");
        m.put("scope", base);
        m.put("display", "standalone");
        m.put("orientation", "portrait");
        m.put("background_color", "#0a0b0d");
        m.put("theme_color", "#0a0b0d");
        m.put("lang", "en-IN");
        m.put("categories", List.of("shopping"));
        m.put("icons", List.of(
                Map.of("src", "/assets/img/" + brand + "-icon-192.png", "sizes", "192x192", "type", "image/png", "purpose", "any"),
                Map.of("src", "/assets/img/" + brand + "-logo-square.png", "sizes", "512x512", "type", "image/png", "purpose", "any")));
        m.put("shortcuts", List.of(
                Map.of("name", "Coupons & offers", "url", base + "/offers"),
                Map.of("name", "Buying guides", "url", base + "/guides"),
                Map.of("name", "Wishlist", "url", base + "/wishlist")));
        return ResponseEntity.ok().contentType(MANIFEST).cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic()).body(m);
    }
}
