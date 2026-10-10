package com.hybrid.platform.research;

import com.hybrid.platform.common.Storefront;

import java.util.List;

/**
 * Curated affiliate-capable platforms and category playbooks.
 * Every platform below was checked against its official program page (or the network that runs it)
 * in October 2026. Programs change: re-check {@link #VERIFIED_ON} entries before relying on them.
 */
final class AffiliatePlatformCatalog {

    static final String VERIFIED_ON = "October 2026";

    /** DIRECT = apply to the brand itself; NETWORK = an affiliate network; VIA_NETWORK = brand only reachable through a network. */
    enum Access { DIRECT, NETWORK, VIA_NETWORK }

    record Platform(String id, String name, String program, Access access, String region, String joinUrl,
                    String requirement, List<String> strengths, List<Storefront> storefronts,
                    List<String> merchantHosts) {
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
                new Platform("amazon-in", "Amazon.in", "Amazon Associates India", Access.DIRECT, "IN",
                        "https://affiliate-program.amazon.in/",
                        "Open sign-up; account is reviewed after your first qualifying sales.",
                        List.of("Huge catalog", "Trusted checkout", "Tag tracking"),
                        List.of(Storefront.SHOP, Storefront.DIGITAL), List.of("amazon.in", "amzn.to")),
                new Platform("flipkart", "Flipkart", "Flipkart via Cuelinks / EarnKaro", Access.VIA_NETWORK, "IN",
                        "https://www.cuelinks.com/",
                        "Direct Flipkart sign-ups have been paused since May 2018; new affiliates join through a network.",
                        List.of("Strong India traffic", "Electronics & fashion", "Deep discounts"),
                        List.of(Storefront.SHOP), List.of("flipkart.com", "fkrt.it")),
                new Platform("myntra", "Myntra", "Myntra via Cuelinks / EarnKaro", Access.VIA_NETWORK, "IN",
                        "https://www.cuelinks.com/campaigns/myntra-affiliate-program",
                        "Commission differs for new vs existing Myntra users; short (about 1 day) cookie.",
                        List.of("Fashion & lifestyle", "Brand depth"),
                        List.of(Storefront.SHOP), List.of("myntra.com")),
                new Platform("ajio", "AJIO", "AJIO via Cuelinks / EarnKaro", Access.VIA_NETWORK, "IN",
                        "https://www.cuelinks.com/campaigns/ajio-affiliate-program",
                        "No direct AJIO program; social-media and paid-search promotion are restricted.",
                        List.of("Apparel", "Private labels"),
                        List.of(Storefront.SHOP), List.of("ajio.com")),
                new Platform("croma", "Croma", "Croma Affiliate Program", Access.DIRECT, "IN",
                        "https://www.croma.com/affiliate-faqs",
                        "Application is reviewed; payouts need bank details, PAN and a cancelled cheque.",
                        List.of("Electronics", "Appliances"),
                        List.of(Storefront.SHOP), List.of("croma.com")),
                new Platform("nykaa", "Nykaa", "Nykaa Affiliate Program (NAP)", Access.DIRECT, "IN",
                        "https://affiliate.nykaa.com/",
                        "Creators need 5,000+ followers; approval can take up to 2 weeks.",
                        List.of("Beauty", "Personal care", "High repeat intent"),
                        List.of(Storefront.SHOP), List.of("nykaa.com", "nykaafashion.com")),
                new Platform("cuelinks", "Cuelinks", "Cuelinks network", Access.NETWORK, "IN",
                        "https://www.cuelinks.com/",
                        "One sign-up gives access to Flipkart, Myntra, AJIO and many Indian brands.",
                        List.of("Covers brands without direct programs", "Deep links", "India payouts"),
                        List.of(Storefront.SHOP, Storefront.DIGITAL), List.of("cuelinks.com")),
                new Platform("earnkaro", "EarnKaro", "EarnKaro network", Access.NETWORK, "IN",
                        "https://earnkaro.com/",
                        "Free sign-up; low withdrawal threshold; profit links for Flipkart, AJIO, Myntra and more.",
                        List.of("Creator friendly", "Fast approval", "Low payout threshold"),
                        List.of(Storefront.SHOP), List.of("earnkaro.com")),
                new Platform("impact", "impact.com", "impact.com partner network", Access.NETWORK, "GLOBAL",
                        "https://impact.com/",
                        "Apply per brand inside the network; each brand approves you separately.",
                        List.of("SaaS & DTC brands", "Reliable tracking", "Many digital offers"),
                        List.of(Storefront.SHOP, Storefront.DIGITAL), List.of()),
                new Platform("cj", "CJ Affiliate", "CJ Affiliate network", Access.NETWORK, "GLOBAL",
                        "https://www.cj.com/",
                        "Apply per advertiser; many programs are US-focused.",
                        List.of("Enterprise brands", "Digital goods", "Finance & software"),
                        List.of(Storefront.DIGITAL, Storefront.SHOP), List.of()),
                new Platform("awin", "Awin", "Awin network (ShareASale merged in 2025)", Access.NETWORK, "GLOBAL",
                        "https://www.awin.com/",
                        "ShareASale closed on 6 Oct 2025; its merchants now run on Awin.",
                        List.of("SMB merchants", "Tools & courses", "Product feeds"),
                        List.of(Storefront.DIGITAL, Storefront.SHOP), List.of()),
                new Platform("udemy", "Udemy", "Udemy Affiliate Program (on impact.com)", Access.VIA_NETWORK, "GLOBAL",
                        "https://www.udemy.com/affiliate/",
                        "Needs a live site with 500+ monthly visitors or 500+ followers; 10% base, 7-day cookie.",
                        List.of("Online courses", "Evergreen niches", "Frequent sales"),
                        List.of(Storefront.DIGITAL), List.of("udemy.com")),
                new Platform("hostinger", "Hostinger", "Hostinger Affiliate Program", Access.DIRECT, "GLOBAL",
                        "https://www.hostinger.com/in/affiliates",
                        "Manual review up to 5 business days; commission starts around 40% of eligible sales.",
                        List.of("High commission", "Beginner hosting intent", "Digital storefront fit"),
                        List.of(Storefront.DIGITAL), List.of("hostinger.com", "hostinger.in"))
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
                    List.of("amazon-in", "croma", "flipkart", "cuelinks")),
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
                    List.of("amazon-in", "croma", "flipkart", "cuelinks")),
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
                    List.of("nykaa", "amazon-in", "cuelinks")),
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
                    List.of("amazon-in", "myntra", "ajio", "cuelinks", "earnkaro")),
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
                    List.of("amazon-in", "flipkart", "earnkaro")),
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
                    List.of("amazon-in", "flipkart", "earnkaro")),
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
                    List.of("impact", "cj", "awin")),
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
                    List.of("udemy", "impact", "awin", "cj")),
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
                    List.of("awin", "impact", "cj")),
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
                    List.of("hostinger", "impact", "cj")),
            pb("apps-utilities", "Apps & utilities", Storefront.DIGITAL,
                    "App stores have no affiliate program; promote apps whose makers run one on impact.com, CJ or Awin.",
                    List.of("best PDF editor Android free vs paid", "scanner app OCR accuracy review",
                            "habit tracker apps comparison 2026", "offline maps app India travel"),
                    List.of(
                            seed("PDF editor / scanner app", "Utility intent + clear comparisons.", 84, 70,
                                    List.of("pdf", "scanner", "ocr", "app")),
                            seed("Habit / focus tracker", "Productivity YouTube overlap.", 78, 65,
                                    List.of("habit", "focus", "pomodoro", "app")),
                            seed("Offline travel maps", "Seasonal travel spikes.", 74, 60,
                                    List.of("maps", "offline", "travel", "app"))),
                    List.of("impact", "cj", "awin"))
    );

    private static CategoryPlaybook pb(String id, String name, Storefront sf, String blurb,
                                       List<String> queries, List<ProductSeed> products, List<String> platforms) {
        return new CategoryPlaybook(id, name, sf, blurb, queries, products, platforms);
    }

    private static ProductSeed seed(String title, String why, int demand, int commission, List<String> tags) {
        return new ProductSeed(title, why, demand, commission, tags);
    }
}
