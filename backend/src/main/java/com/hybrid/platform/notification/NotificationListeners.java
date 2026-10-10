package com.hybrid.platform.notification;

import com.hybrid.platform.affiliate.AffiliateEvents;
import com.hybrid.platform.commerce.cart.CartRepository;
import com.hybrid.platform.common.AppProperties;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.commerce.order.OrderEvents;
import com.hybrid.platform.identity.IdentityEvents;
import com.hybrid.platform.notification.NotificationRepositories.NotificationLogRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/** Emails are sent only after the business transaction commits, on a background thread. */
@Component
public class NotificationListeners {

    static final int PRICE_DROP_MAX_RECIPIENTS = 500;
    static final Duration PRICE_DROP_COOLDOWN = Duration.ofDays(7);

    private final MailService mail;
    private final AppProperties props;
    private final CartRepository carts;
    private final NotificationLogRepository logs;

    public NotificationListeners(MailService mail, AppProperties props, CartRepository carts, NotificationLogRepository logs) {
        this.mail = mail;
        this.props = props;
        this.carts = carts;
        this.logs = logs;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAffiliatePriceDropped(AffiliateEvents.PriceDropped e) {
        String subject = "Price drop: " + e.productTitle();
        if (subject.length() > 250) subject = subject.substring(0, 247) + "...";
        Instant since = Instant.now().minus(PRICE_DROP_COOLDOWN);
        String link = base() + "/" + e.storefront().path() + "/cart";
        int sent = 0;
        for (CartRepository.SavedBy c : carts.customersWithProduct(e.storefront().name(), e.productId())) {
            if (sent >= PRICE_DROP_MAX_RECIPIENTS) break;
            if (logs.existsByStorefrontAndRecipientAndKindAndSubjectAndCreatedAtAfter(e.storefront(), c.getEmail(),
                    "PRICE_DROP", subject, since)) {
                continue;
            }
            mail.send(e.storefront(), c.getEmail(), subject,
                    "Hi " + c.getFullName() + ",\n\nGood news: " + e.productTitle() + ", which you saved in your cart, dropped from "
                            + money(e.oldPriceMinor(), "INR") + " to " + money(e.newPriceMinor(), "INR") + " at "
                            + e.merchantName() + ".\n\nPartner prices change often, so check the final price on "
                            + e.merchantName() + " before you buy:\n" + link
                            + "\n\nYou get these alerts because the item is in your cart. Remove it from your cart to stop them.",
                    "PRICE_DROP");
            sent++;
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRegistered(IdentityEvents.CustomerRegistered e) {
        Storefront sf = e.storefront() == null ? Storefront.SHOP : e.storefront();
        String link = base() + "/account/verify.html?token=" + enc(e.verifyToken());
        mail.send(sf, e.email(), "Confirm your email address",
                "Hi " + e.fullName() + ",\n\nWelcome! Please confirm your email address by opening this link:\n" + link
                        + "\n\nThe link expires in 48 hours. If you did not create an account, ignore this email.", "EMAIL_VERIFY");
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPasswordReset(IdentityEvents.PasswordResetRequested e) {
        Storefront sf = e.storefront() == null ? Storefront.SHOP : e.storefront();
        String link = base() + "/account/reset.html?token=" + enc(e.resetToken());
        mail.send(sf, e.email(), "Reset your password",
                "Hi " + e.fullName() + ",\n\nUse this link to choose a new password:\n" + link
                        + "\n\nThe link expires in 30 minutes. If you did not ask for this, you can ignore this email.", "PASSWORD_RESET");
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrderPaid(OrderEvents.OrderPaid e) {
        StringBuilder body = new StringBuilder()
                .append("Hi ").append(e.customerName()).append(",\n\nThank you! We received your payment of ")
                .append(money(e.totalMinor(), e.currency())).append(" for order ").append(e.orderNumber()).append(".\n\n");
        if (!e.digitalLines().isEmpty()) {
            body.append("Your digital items are ready in your library:\n").append(base()).append("/account/library.html\n\n");
        }
        if (e.requiresShipping()) {
            body.append("We will email you again when your items ship.\n\n");
        }
        body.append("Order details and invoice: ").append(base()).append("/account/order.html?n=").append(enc(e.orderNumber()));
        mail.send(e.storefront(), e.customerEmail(), "Order " + e.orderNumber() + " confirmed", body.toString(), "ORDER_PAID");
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCodConfirmed(OrderEvents.CodOrderConfirmed e) {
        mail.send(e.storefront(), e.customerEmail(), "Order " + e.orderNumber() + " confirmed (cash on delivery)",
                "Hi " + e.customerName() + ",\n\nThank you! Your order " + e.orderNumber() + " is confirmed.\n"
                        + "Please keep " + money(e.totalMinor(), e.currency()) + " ready to pay when it is delivered.\n\n"
                        + "We will email you again when your items ship.\n\nOrder details: " + base()
                        + "/account/order.html?n=" + enc(e.orderNumber()), "ORDER_COD_CONFIRMED");
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onShipped(OrderEvents.ShipmentDispatched e) {
        StringBuilder body = new StringBuilder()
                .append("Hi ").append(e.customerName()).append(",\n\nYour order ").append(e.orderNumber())
                .append(" has shipped with ").append(e.carrier()).append(".\n");
        if (e.trackingNumber() != null) body.append("Tracking number: ").append(e.trackingNumber()).append("\n");
        if (e.trackingUrl() != null) body.append("Track it here: ").append(e.trackingUrl()).append("\n");
        body.append("\nOrder details: ").append(base()).append("/account/order.html?n=").append(enc(e.orderNumber()));
        mail.send(e.storefront(), e.customerEmail(), "Order " + e.orderNumber() + " has shipped", body.toString(), "ORDER_SHIPPED");
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRefunded(OrderEvents.OrderRefunded e) {
        mail.send(e.storefront(), e.customerEmail(), "Refund for order " + e.orderNumber(),
                "Hi " + e.customerName() + ",\n\nWe have refunded " + money(e.amountMinor(), e.currency())
                        + " for order " + e.orderNumber() + ". Depending on your bank it can take 5-7 working days to appear.",
                "ORDER_REFUNDED");
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCancelled(OrderEvents.OrderCancelled e) {
        mail.send(e.storefront(), e.customerEmail(), "Order " + e.orderNumber() + " cancelled",
                "Hi " + e.customerName() + ",\n\nYour order " + e.orderNumber() + " was cancelled. Reason: " + e.reason()
                        + "\n\nNo payment was taken for this order.", "ORDER_CANCELLED");
    }

    private String base() {
        String b = props.baseUrl() == null ? "" : props.baseUrl();
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    static String money(long minor, String currency) {
        String symbol = "INR".equals(currency) ? "₹" : currency + " ";
        return symbol + String.format(Locale.ROOT, "%,.2f", minor / 100.0);
    }
}
