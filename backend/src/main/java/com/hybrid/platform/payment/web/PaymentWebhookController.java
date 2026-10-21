package com.hybrid.platform.payment.web;

import com.hybrid.platform.payment.PaymentCallbackService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Gateway webhooks. Public and CSRF-exempt; every request must carry a valid gateway signature over the raw body. */
@RestController
public class PaymentWebhookController {

    private final PaymentCallbackService callbacks;

    public PaymentWebhookController(PaymentCallbackService callbacks) {
        this.callbacks = callbacks;
    }

    @PostMapping("/api/payments/webhooks/{provider}")
    public ResponseEntity<Void> webhook(@PathVariable String provider, @RequestBody byte[] body,
                                        @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
        callbacks.handleWebhook(provider, body, signature);
        return ResponseEntity.ok().build();
    }
}
