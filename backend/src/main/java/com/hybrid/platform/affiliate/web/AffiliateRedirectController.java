package com.hybrid.platform.affiliate.web;

import com.hybrid.platform.affiliate.AffiliateService;
import com.hybrid.platform.security.CurrentUser;
import com.hybrid.platform.security.CustomerPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
public class AffiliateRedirectController {

    private final AffiliateService service;

    public AffiliateRedirectController(AffiliateService service) {
        this.service = service;
    }

    @GetMapping("/go/{code}")
    public ResponseEntity<Void> go(@PathVariable String code, HttpServletRequest request) {
        Long customerId = CurrentUser.optionalCustomer().map(CustomerPrincipal::id).orElse(null);
        var ctx = new AffiliateService.ClickContext(customerId, request.getRemoteAddr(),
                request.getHeader(HttpHeaders.USER_AGENT), request.getHeader(HttpHeaders.REFERER));
        return service.resolveRedirect(code, ctx)
                .map(url -> ResponseEntity.status(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION, url)
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .header("Referrer-Policy", "no-referrer-when-downgrade")
                        .header("X-Robots-Tag", "noindex, nofollow")
                        .<Void>build())
                .orElseGet(() -> ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create("/link-unavailable.html"))
                        .build());
    }
}
