package com.hybrid.platform.notification;

import com.hybrid.platform.common.AppProperties;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.commerce.order.OrderEvents;
import com.hybrid.platform.identity.IdentityEvents;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Emails are sent only after the business transaction commits, on a background thread. */
@Component
public class NotificationListeners {

    private final MailService mail;
    private final AppProperties props;

    public NotificationListeners(MailService mail, AppProperties props) {
        this.mail = mail;
        this.props = props;
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
