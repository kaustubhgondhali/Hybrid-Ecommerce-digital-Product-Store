package com.hybrid.platform.research;

import com.hybrid.platform.common.Storefront;

import java.util.List;

/**
 * Curated affiliate-capable platforms and category playbooks.
 * Only networks/marketplaces with a real affiliate or partner program are listed.
 */
final class AffiliatePlatformCatalog {

    record Platform(String id, String name, String program, String region, String joinUrl,
                    String searchUrlTemplate, List<String> strengths, List<Storefront> storefronts) {
    }

    record ProductSeed(String title, String why, int demandScore, int commissionHint, List<String> tags) {
    }

    record CategoryPlaybook(String id, String name, Storefront storefront, String blurb,
                            List<String> googleQueries, List<ProductSeed> products, List<String> platformIds) {
    }

    private AffiliatePlatformCatalog() {
    }

    static List<Platform> platforms() {
        return List.of(
                new Platform("amazon-in", "Amazon.in", "Amazon Associates (India)", "IN",
                        "https://affiliate-program.amazon.in/",
                        "https://www.amazon.in/s?k={q}",
                        List.of("Huge catalog", "Trusted checkout", "Tag tracking"),
                        List.of(Storefront.SHOP, Storefront.DIGITAL)),
                new Platform("flipkart", "Flipkart", "Flipkart Affiliate", "IN",
                        "https://affiliate.flipkart.com/",
                        "https://www.flipkart.com/search?q={q}",
                        List.of("Strong India traffic", "Electronics & fashion", "Deep discounts"),
                        List.of(Storefront.SHOP)),
                new Platform("myntra", "Myntra", "Flipkart Group / partner programs", "IN",
                        "https://affiliate.flipkart.com/",
                        "https://www.myntra.com/{q}",
                        List.of("Fashion & lifestyle", "Brand depth"),
                        List.of(Storefront.SHOP)),
                new Platform("ajio", "AJIO", "Reliance / partner affiliate", "IN",
                        "https://www.ajio.com/",
                        "https://www.ajio.com/search/?text={q}",
                        List.of("Apparel", "Private labels"),
                        List.of(Storefront.SHOP)),
                new Platform("croma", "Croma", "Tata Neu / partner links", "IN",
                        "https://www.croma.com/",
                        "https://www.croma.com/search/?q={q}",
                        List.of("Electronics", "Appliances"),
                        List.of(Storefront.SHOP)),
                new Platform("nykaa", "Nykaa", "Nykaa Affiliate", "IN",
                        "https://www.nykaa.com/",
                        "https://www.nykaa.com/search/result/?q={q}",
                        List.of("Beauty", "Personal care", "High repeat intent"),
                        List.of(Storefront.SHOP)),
                new Platform("impact", "Impact.com brands", "Impact partner network", "GLOBAL",
                        "https://impact.com/",
                        "https://www.google.com/search?q={q}+site%3Aimpact.com",
                        List.of("SaaS & DTC brands", "Reliable tracking", "Many digital offers"),
                        List.of(Storefront.SHOP, Storefront.DIGITAL)),
                new Platform("cj", "CJ Affiliate", "Commission Junction", "GLOBAL",
                        "https://www.cj.com/",
                        "https://www.google.com/search?q={q}+CJ+affiliate",
                        List.of("Enterprise brands", "Digital goods", "Finance & software"),
                        List.of(Storefront.DIGITAL, Storefront.SHOP)),
                new Platform("shareasale", "ShareASale", "ShareASale network", "GLOBAL",
                        "https://www.shareasale.com/",
                        "https://www.google.com/search?q={q}+ShareASale",
                        List.of("SMB merchants", "Tools & courses", "Flexible creatives"),
                        List.of(Storefront.DIGITAL)),
                new Platform("appstore", "Apple App Store / Google Play", "Select developer affiliate / ASA", "GLOBAL",
                        "https://developer.apple.com/",
                        "https://www.google.com/search?q={q}+app+affiliate+program",
                        List.of("Apps & utilities", "Digital delivery"),
                        List.of(Storefront.DIGITAL)),
                new Platform("udemy", "Udemy / course marketplaces", "Udemy Affiliate & similar", "GLOBAL",
                        "https://www.udemy.com/",
                        "https://www.udemy.com/courses/search/?q={q}",
                        List.of("Online courses", "Evergreen niches", "High AOV carts"),
                        List.of(Storefront.DIGITAL)),
                new Platform("hosting", "Hosting & SaaS (Namecheap, Hostinger, Notion…)", "Vendor affiliate programs", "GLOBAL",
                        "https://www.google.com/search?q=best+SaaS+affiliate+programs",
                        "https://www.google.com/search?q={q}+affiliate+program",
                        List.of("Recurring commissions", "B2B intent", "Digital storefront fit"),
                        List.of(Storefront.DIGITAL))
        );
    }

    static List<CategoryPlaybook> playbooks(Storefront sf) {
        return ALL.stream().filter(p -> p.storefront() == sf).toList();
    }

    private static final List<CategoryPlaybook> ALL = List.of(
            // ---- SHOP
            pb("electronics", "Electronics & gadgets", Storefront.SHOP,
                    "High search volume; pair reviews with Amazon/Flipkart affiliate links.",
                    List.of("best wireless earbuds under 3000 India 2026", "noise cancelling headphones review India",
                            "power bank 20000mah battery test", "smart watch accuracy review India"),
                    List.of(
                            seed("ANC wireless earbuds", "Constant review coverage and clear comparison angles.", 92, 80,
                                    List.of("earbuds", "headphones", "anc", "bluetooth")),
                            seed("Fast-charge power bank 20,000 mAh", "Travel + WFH evergreen demand.", 88, 70,
                                    List.of("power bank", "charger", "battery")),
                            seed("Smart fitness watch", "Review-led category; buyers compare SpO2/GPS claims.", 85, 75,
                                    List.of("smartwatch", "fitness", "band")),
                            seed("USB-C multiport hub", "Desk setup content converts well.", 78, 65,
                                    List.of("hub", "usb-c", "dock", "laptop"))),
                    List.of("amazon-in", "flipkart", "croma")),
            pb("home-kitchen", "Home & kitchen", Storefront.SHOP,
                    "Review videos and Google Shopping intent are strong; Flipkart/Amazon dominate.",
                    List.of("best air fryer India review", "mixer grinder 750W comparison",
                            "robot vacuum India worth it", "induction cooktop safety review"),
                    List.of(
                            seed("Compact air fryer", "Recipe + review content drives affiliate clicks.", 90, 72,
                                    List.of("air fryer", "kitchen", "appliance")),
                            seed("Mixer grinder (ISI)", "Staple Indian kitchen purchase with brand debates.", 86, 68,
                                    List.of("mixer", "grinder", "kitchen")),
                            seed("Electric kettle with temperature control", "Gift + WFH niche.", 74, 60,
                                    List.of("kettle", "tea", "coffee")),
                            seed("Robot vacuum entry model", "High curiosity; strong review dependency.", 82, 70,
                                    List.of("vacuum", "robot", "cleaning"))),
                    List.of("amazon-in", "flipkart", "croma")),
            pb("beauty", "Beauty & personal care", Storefront.SHOP,
                    "Nykaa + Amazon; reviews and ingredient deep-dives convert.",
                    List.of("best sunscreen for oily skin India", "niacinamide serum review India",
                            "hair serum frizz control before after", "vitamin C serum oxidation tips"),
                    List.of(
                            seed("Broad-spectrum sunscreen SPF 50", "Year-round India demand; review-heavy.", 91, 78,
                                    List.of("sunscreen", "spf", "skincare")),
                            seed("Niacinamide / vitamin C serum", "Ingredient education content pairs well.", 87, 74,
                                    List.of("serum", "niacinamide", "skincare")),
                            seed("Anti-frizz hair serum", "Seasonal humidity spikes search.", 79, 66,
                                    List.of("hair", "serum", "frizz")),
                            seed("Electric facial cleanser", "Gadget-adjacent beauty; giftable.", 72, 62,
                                    List.of("facial", "cleanser", "beauty"))),
                    List.of("nykaa", "amazon-in", "flipkart")),
            pb("fashion", "Fashion & accessories", Storefront.SHOP,
                    "Myntra/AJIO/Amazon; style guides + size reviews matter.",
                    List.of("best running shoes under 5000 India", "leather wallet RFID review",
                            "travel backpack 40L comparison", "winter jacket India brands"),
                    List.of(
                            seed("Everyday running shoes", "Size/fit reviews decide conversion.", 84, 70,
                                    List.of("shoes", "running", "sneakers")),
                            seed("RFID-blocking leather wallet", "Compact AOV, easy content.", 76, 58,
                                    List.of("wallet", "leather", "rfid")),
                            seed("Carry-on travel backpack", "Travel season spikes.", 83, 68,
                                    List.of("backpack", "travel", "luggage")),
                            seed("UV-cut sunglasses", "Summer + travel content.", 71, 55,
                                    List.of("sunglasses", "uv", "fashion"))),
                    List.of("myntra", "ajio", "amazon-in", "flipkart")),
            pb("baby-parenting", "Baby & parenting", Storefront.SHOP,
                    "Trust-heavy; Google reviews and pediatric content influence buys.",
                    List.of("best baby stroller India lightweight", "diaper rash cream dermatologist",
                            "baby monitor wifi privacy review", "sterilizer vs boiling bottles"),
                    List.of(
                            seed("Lightweight stroller", "Parents research heavily before buying.", 88, 72,
                                    List.of("stroller", "baby", "pram")),
                            seed("Bottle sterilizer", "Utility + safety reviews.", 80, 64,
                                    List.of("sterilizer", "bottle", "baby")),
                            seed("Baby monitor with app", "Privacy reviews are a content angle.", 77, 66,
                                    List.of("monitor", "baby", "camera"))),
                    List.of("amazon-in", "flipkart")),
            pb("fitness", "Fitness & outdoors", Storefront.SHOP,
                    "Review + transformation content; Amazon/Flipkart primary.",
                    List.of("adjustable dumbbells home gym India", "yoga mat non slip review",
                            "resistance bands set worth it", "foam roller recovery guide"),
                    List.of(
                            seed("Adjustable dumbbell set", "Home gym evergreen.", 86, 70,
                                    List.of("dumbbell", "gym", "weights")),
                            seed("Non-slip yoga mat 6mm+", "High volume, low friction listing.", 81, 60,
                                    List.of("yoga", "mat", "fitness")),
                            seed("Resistance band kit", "Beginner content converts.", 78, 58,
                                    List.of("resistance", "bands", "workout"))),
                    List.of("amazon-in", "flipkart")),
            // ---- DIGITAL
            pb("software-saas", "Software & SaaS tools", Storefront.DIGITAL,
                    "Recurring commissions; Impact/CJ/vendor programs beat marketplaces.",
                    List.of("best project management tools for freelancers 2026", "Notion vs Obsidian for students",
                            "AI writing assistant comparison for marketers", "password manager family plan review"),
                    List.of(
                            seed("AI writing / productivity suite", "Review roundups drive affiliate SaaS.", 93, 90,
                                    List.of("ai", "writing", "productivity", "saas")),
                            seed("Password manager (family)", "Security content + recurring payouts.", 88, 85,
                                    List.of("password", "security", "manager")),
                            seed("Design / creative subscription", "Tutorial content → trial signups.", 84, 80,
                                    List.of("design", "canva", "creative")),
                            seed("VPN annual plan", "Privacy guides convert year-round.", 82, 78,
                                    List.of("vpn", "privacy", "security"))),
                    List.of("impact", "cj", "shareasale", "hosting")),
            pb("courses", "Online courses & learning", Storefront.DIGITAL,
                    "Udemy and creator programs; Google “best course for X” intent is strong.",
                    List.of("best Python course for beginners 2026", "digital marketing certification worth it",
                            "Excel for data analysis course review", "spoken English course online India"),
                    List.of(
                            seed("Career Python / data course", "Job-intent searches convert.", 90, 82,
                                    List.of("python", "course", "data", "coding")),
                            seed("Digital marketing certificate path", "Evergreen career niche.", 86, 78,
                                    List.of("marketing", "seo", "ads", "course")),
                            seed("Business Excel / analytics", "Office workers research heavily.", 83, 74,
                                    List.of("excel", "analytics", "business")),
                            seed("Language / spoken English pack", "India search volume is large.", 80, 70,
                                    List.of("english", "language", "spoken"))),
                    List.of("udemy", "impact", "shareasale", "cj")),
            pb("templates-assets", "Templates, assets & ebooks", Storefront.DIGITAL,
                    "Creator marketplaces + own affiliate links; pair with how-to content.",
                    List.of("best Notion template for students", "Instagram reel templates pack review",
                            "resume template ATS friendly 2026", "wedding invitation digital templates India"),
                    List.of(
                            seed("Notion / productivity template pack", "Creators already review these.", 85, 72,
                                    List.of("notion", "template", "productivity")),
                            seed("Social media templates pack", "Visual demos convert.", 82, 68,
                                    List.of("instagram", "templates", "reels")),
                            seed("ATS resume + cover letter kit", "Job season spikes.", 84, 70,
                                    List.of("resume", "cv", "job", "ats")),
                            seed("Printable planner / journal PDF", "Low-price impulse + reviews.", 76, 60,
                                    List.of("planner", "printable", "pdf", "ebook"))),
                    List.of("shareasale", "impact", "cj")),
            pb("hosting-domains", "Hosting, domains & creator tools", Storefront.DIGITAL,
                    "High commission; Google “best hosting India” style queries.",
                    List.of("best web hosting India for beginners", "cheap domain registration India comparison",
                            "WordPress hosting vs static site 2026", "email marketing tool for small business India"),
                    List.of(
                            seed("Shared / cloud hosting plan", "Classic high-commission niche.", 91, 92,
                                    List.of("hosting", "wordpress", "website")),
                            seed("Domain + email starter", "Bundle with how-to guides.", 79, 75,
                                    List.of("domain", "email", "dns")),
                            seed("Email marketing platform", "SMB content converts on trial.", 86, 84,
                                    List.of("email", "marketing", "newsletter"))),
                    List.of("hosting", "impact", "cj")),
            pb("apps-utilities", "Apps & utilities", Storefront.DIGITAL,
                    "Only list apps with disclosed affiliate or partner programs.",
                    List.of("best PDF editor Android free vs paid", "scanner app OCR accuracy review",
                            "habit tracker apps comparison 2026", "offline maps app India travel"),
                    List.of(
                            seed("PDF editor / scanner app", "Utility intent + clear comparisons.", 84, 70,
                                    List.of("pdf", "scanner", "ocr", "app")),
                            seed("Habit / focus tracker", "Productivity YouTube overlap.", 78, 65,
                                    List.of("habit", "focus", "pomodoro", "app")),
                            seed("Offline travel maps", "Seasonal travel spikes.", 74, 60,
                                    List.of("maps", "offline", "travel", "app"))),
                    List.of("appstore", "impact", "cj"))
    );

    private static CategoryPlaybook pb(String id, String name, Storefront sf, String blurb,
                                       List<String> queries, List<ProductSeed> products, List<String> platforms) {
        return new CategoryPlaybook(id, name, sf, blurb, queries, products, platforms);
    }

    private static ProductSeed seed(String title, String why, int demand, int commission, List<String> tags) {
        return new ProductSeed(title, why, demand, commission, tags);
    }
}
