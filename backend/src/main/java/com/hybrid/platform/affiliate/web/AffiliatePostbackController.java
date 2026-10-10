package com.hybrid.platform.affiliate.web;

import com.hybrid.platform.affiliate.AffiliateService;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Server-to-server conversion postback for affiliate networks. Networks call this URL with a shared secret
 * (query parameter {@code token} or header {@code X-Postback-Token}); it is disabled while no secret is configured.
 * Accepts GET and form POST because networks differ in what they send.
 */
@RestController
@RequestMapping("/api/{storefront}/affiliate/postback")
public class AffiliatePostbackController {

    private static final Logger log = LoggerFactory.getLogger(AffiliatePostbackController.class);

    private final AffiliateService service;
    private final byte[] token;

    public AffiliatePostbackController(AffiliateService service,
                                       @Value("${app.affiliate.postback-token:}") String token) {
        this.service = service;
        this.token = token == null || token.isBlank() ? null : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    @RequestMapping(method = {RequestMethod.GET, RequestMethod.POST})
    public AffiliateService.PostbackResult postback(
            @PathVariable String storefront,
            @RequestParam(name = "token", required = false) String queryToken,
            @RequestHeader(name = "X-Postback-Token", required = false) String headerToken,
            @RequestParam(name = "txn", required = false) String txn,
            @RequestParam(name = "click_ref", required = false) String clickRef,
            @RequestParam(name = "sale", required = false) String sale,
            @RequestParam(name = "commission", required = false) String commission,
            @RequestParam(name = "status", required = false) String status) {
        Storefront sf = Storefront.fromPath(storefront);
        if (token == null) {
            throw ApiException.notFound("NOT_FOUND", "Not found");
        }
        String supplied = headerToken != null ? headerToken : queryToken;
        if (supplied == null || !MessageDigest.isEqual(token, supplied.trim().getBytes(StandardCharsets.UTF_8))) {
            log.warn("Rejected affiliate postback for /{} with an invalid token", sf.path());
            throw new ApiException(HttpStatus.FORBIDDEN, "POSTBACK_TOKEN_INVALID", "Invalid postback token");
        }
        AffiliateService.PostbackResult r = service.recordPostback(sf, txn, clickRef, sale, commission, status);
        log.info("Affiliate postback /{} txn={} status={} created={} matchedClick={}",
                sf.path(), r.externalTxnId(), r.status(), r.created(), r.matchedClick());
        return r;
    }
}
