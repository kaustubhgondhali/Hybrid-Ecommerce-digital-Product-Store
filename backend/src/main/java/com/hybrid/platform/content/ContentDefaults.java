package com.hybrid.platform.content;

import com.hybrid.platform.common.Storefront;

import java.util.List;

/**
 * Starter policy pages created once per storefront. They are templates: the store owner must review them
 * (ideally with a lawyer) and fill in business details before launch.
 */
final class ContentDefaults {

    record Template(String slug, String title, String metaDescription, String body) {
    }

    private ContentDefaults() {
    }

    private static final String REVIEW_NOTE = """
            > Template: review this page and replace every [bracketed] value with your business details before launch.
            """;

    static List<Template> forStorefront(Storefront sf) {
        boolean shop = sf == Storefront.SHOP;
        return List.of(
                new Template("about-us", "About us",
                        "Learn who we are, what we sell and how we look after our customers.",
                        REVIEW_NOTE + """
                        # Who we are
                        {{STORE}} is operated by [legal business name], [city], India. We %s

                        # Why shop with us
                        - Prices, stock and order status are always confirmed by our servers, never guessed.
                        - Secure payments and GST invoices for every order.
                        - Real people answer your questions at {{EMAIL}}.
                        """.formatted(shop
                                ? "curate audio, tech and lifestyle products and deliver them across India, and we also recommend products from trusted partner stores."
                                : "sell software, licences, courses and e-books that are delivered instantly to your account library.")),
                new Template("contact-us", "Contact us",
                        "How to reach our support team by email or phone, and our business address.",
                        REVIEW_NOTE + """
                        # Get in touch
                        Email: {{EMAIL}}
                        Phone: [+91 XXXXX XXXXX] ([days and hours, e.g. Mon-Sat 10am-6pm IST])

                        # Business address
                        [Legal business name]
                        [Street address]
                        [City, State, PIN code]
                        GSTIN: [your GSTIN]

                        # Grievance officer
                        As required by the Consumer Protection (E-Commerce) Rules, 2020:
                        [Name], [designation], {{EMAIL}}. We acknowledge complaints within 48 hours and resolve them within one month.
                        """),
                new Template("privacy-policy", "Privacy policy",
                        "What personal data we collect, why we collect it, and how you can control it.",
                        REVIEW_NOTE + """
                        # Information we collect
                        - Account details: name, email address, phone number and password (stored only as a secure hash).
                        - Order details: shipping address, items purchased and payment status. We never see or store your full card or UPI details; payments are processed by our payment partner.
                        - Technical data: IP address and browser information, used for security and fraud prevention.
                        - Affiliate links: when you click a partner offer we record the click (with a hashed IP address) so we can measure which recommendations are useful.

                        # How we use it
                        - To process and deliver your orders and send order emails.
                        - To provide customer support and handle refunds.
                        - To keep the site secure and prevent fraud.
                        - To meet legal and tax obligations (for example GST invoices).

                        # Sharing
                        We share data only with service providers needed to run the store (payment gateway, courier partners, email provider) and with authorities when required by law. We do not sell your personal data.

                        # Cookies
                        We use essential cookies to keep you signed in and to protect forms against forgery. We do not use advertising cookies.

                        # Your rights
                        You can view and update your profile in your account, and request deletion of your account by writing to {{EMAIL}}. Some order records must be kept for tax purposes.

                        # Contact
                        Questions about this policy: {{EMAIL}}.
                        """),
                new Template("terms-and-conditions", "Terms and conditions",
                        "The terms that apply when you use this website and place an order.",
                        REVIEW_NOTE + """
                        # Using this website
                        By using {{STORE}} you agree to these terms. You must be at least 18 years old, or use the site with a parent's or guardian's consent.

                        # Orders and pricing
                        - All prices are in Indian Rupees (INR). Prices and availability are confirmed when you place an order.
                        - An order is accepted when payment is confirmed (or, for cash on delivery, when we confirm dispatch).
                        - We may cancel an order in case of a pricing error, stock problem or suspected fraud, with a full refund.

                        # Partner (affiliate) offers
                        Some products are sold by partner stores. Clicking "Buy" on those products takes you to the partner's website; that purchase is governed by the partner's terms, and the partner is responsible for the product, delivery and returns.

                        # Accounts
                        Keep your password confidential. You are responsible for activity on your account.

                        # Limitation of liability
                        To the extent permitted by law, our liability for any order is limited to the amount paid for that order.

                        # Governing law
                        These terms are governed by the laws of India. Courts at [city] have jurisdiction.
                        """),
                new Template("refund-policy", "Refund and cancellation policy",
                        "When you can cancel an order, how returns work, and how long refunds take.",
                        REVIEW_NOTE + (shop ? """
                        # Cancellation
                        Unpaid orders are cancelled automatically after 15 minutes. Paid orders can be cancelled before they are shipped by writing to {{EMAIL}}.

                        # Returns
                        - You can request a return within [7] days of delivery if the item is damaged, defective or different from what you ordered.
                        - Items must be unused and in their original packaging, with all accessories.
                        - [List any non-returnable items, e.g. earphones for hygiene reasons once opened.]

                        # Refunds
                        Approved refunds are issued to the original payment method within [5-7] business days. Cash on delivery orders are refunded by bank transfer or UPI.

                        # Partner products
                        Products bought from partner stores through our links are returned and refunded by that partner under their policy.
                        """ : """
                        # Digital products
                        Because digital products and licence keys are delivered instantly, purchases are generally non-refundable once the file has been downloaded or the key revealed.

                        # When we refund
                        - The product does not work as described and our support team cannot fix it within [7] days.
                        - You were charged twice or charged for an order that was not delivered.
                        Write to {{EMAIL}} within [7] days of purchase with your order number.

                        # Refund timeline
                        Approved refunds are issued to the original payment method within [5-7] business days. Access to the refunded product is removed.
                        """)),
                new Template(shop ? "shipping-policy" : "delivery-policy", shop ? "Shipping policy" : "Delivery policy",
                        shop ? "Shipping charges, delivery areas and delivery times." : "How and when digital products are delivered.",
                        REVIEW_NOTE + (shop ? """
                        # Where we deliver
                        We deliver across India to serviceable PIN codes. Enter your PIN code on any product page to check delivery.

                        # Charges
                        Shipping charges are shown at checkout before you pay. Orders above the free-shipping threshold ship free.

                        # Delivery time
                        Orders are usually dispatched within [1-2] business days and delivered within [3-7] business days depending on your location. You will receive tracking details by email once the order ships.

                        # Cash on delivery
                        Where available, cash on delivery may include a small handling fee shown at checkout.
                        """ : """
                        # Instant delivery
                        Digital products are delivered to your account library as soon as payment is confirmed. You will also receive an email.

                        # Downloads and licence keys
                        - Download links are generated securely and expire after a few minutes; you can generate a new link from your library at any time.
                        - Licence keys are shown in your library and in your order details.

                        # Problems
                        If a product does not appear in your library within 30 minutes of payment, write to {{EMAIL}} with your order number.
                        """)),
                new Template("affiliate-disclosure", "Affiliate disclosure",
                        "How we earn commission from partner links and how it affects you.",
                        REVIEW_NOTE + """
                        # How partner links work
                        Some products on {{STORE}} are marked as partner offers. When you click "Buy" on these products, you are taken to the partner's store (for example Amazon, Flipkart or other retailers). If you buy something, we may earn a commission.

                        # What it means for you
                        - You pay the same price as you would going to the partner directly.
                        - Partner offers are clearly labelled on each product page.
                        - We recommend products we believe are useful; commission does not change our opinion.

                        # Programmes we take part in
                        [List the affiliate programmes you have joined, e.g. "Amazon Associates: As an Amazon Associate we earn from qualifying purchases."]
                        """));
    }
}
