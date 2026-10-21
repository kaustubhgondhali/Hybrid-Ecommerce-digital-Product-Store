package com.hybrid.platform.studio;

import com.hybrid.platform.catalog.CatalogPublicService;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Writes marketing content from product data with fixed templates: no AI service, no paid API. Every sentence is
 * built from facts on the product page (title, brand, category, summary, highlights, pros and cons, specifications,
 * price, partner store), and sections without data are left out. {@code variant} picks between alternative wordings,
 * so "another version" gives different phrasing for the same facts.
 */
public final class ContentWriter {

    public enum BlogKind { REVIEW, WORTH_IT, FAQ, BUYING_TIPS, COMPARISON, ROUNDUP }

    public enum EmailKind { LAUNCH, NEW_ARRIVALS, TOP_DEALS, COLLECTION }

    /** Storefront facts the templates need. {@code url} has no trailing slash. */
    public record Store(String name, String url, boolean digital, LocalDate today) {
    }

    /** Buying-guide markup (see GuideService) plus its search title and description. */
    public record BlogDraft(String kind, String title, String slug, String summary, String body, String metaTitle,
                            String metaDescription) {
    }

    /** Plain-text campaign; {name}, {store} and {link} are filled in per recipient when it is sent. */
    public record EmailDraft(String kind, String name, String subject, String body) {
    }

    /** {@code shareUrl} opens the network's free share dialog; null when the network has none (Instagram). */
    public record SocialPost(String channel, String label, String text, String shareUrl, int maxLength) {
    }

    private static final DateTimeFormatter MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final Set<String> STOP = Set.of("the", "and", "for", "with", "from", "your", "you", "this", "that", "into",
            "best", "new", "pro", "plus", "max", "mini", "edition", "pack", "set", "of", "to", "in", "on", "by", "a", "an", "&");

    private ContentWriter() {
    }

    // ================================================================== blog posts

    public static BlogDraft blog(BlogKind kind, ProductFacts p, List<ProductFacts> related, Store store, int variant) {
        return switch (kind) {
            case REVIEW -> review(p, related, store, variant);
            case WORTH_IT -> worthIt(p, related, store, variant);
            case FAQ -> faqArticle(p, store, variant);
            case BUYING_TIPS -> buyingTips(p, related, store, variant);
            case COMPARISON, ROUNDUP -> throw new IllegalArgumentException(kind + " needs several products");
        };
    }

    static BlogDraft review(ProductFacts p, List<ProductFacts> related, Store store, int v) {
        String name = p.displayName();
        String title = pick(v, name + " review: features, pros and cons",
                name + " review: is it right for you?",
                name + " review (" + store.today().format(MONTH_YEAR) + "): what you should know");
        StringBuilder b = new StringBuilder();
        b.append(intro(p, store, v)).append("\n\n");
        b.append("[product: ").append(p.slug()).append("]\n\n");
        if (p.verdict() != null) b.append("> Quick verdict: ").append(sentence(p.verdict())).append("\n\n");
        atAGlance(b, p, store);
        if (!p.highlights().isEmpty()) {
            b.append("# ").append(pick(v, "Key features", "What you get", "Highlights")).append("\n\n");
            p.highlights().forEach(h -> b.append("- ").append(h).append("\n"));
            b.append("\n");
        }
        prosCons(b, p, v);
        whoFor(b, p, v);
        priceSection(b, p, store, v);
        faq(b, p, store, v);
        b.append("# ").append(pick(v, "Final verdict", "Our take", "Bottom line")).append("\n\n");
        b.append(conclusion(p, store, v)).append("\n\n");
        b.append("[product: ").append(p.slug()).append("]\n");
        alternatives(b, p, related, v);
        disclosure(b, p);
        return draft(BlogKind.REVIEW, title, slugFor(p.title() + " review"), summary(p, v), b.toString(), p, store);
    }

    static BlogDraft worthIt(ProductFacts p, List<ProductFacts> related, Store store, int v) {
        String name = p.displayName();
        String title = pick(v, "Is the " + name + " worth buying?", "Should you buy the " + name + "?",
                name + ": worth it in " + store.today().getYear() + "?");
        StringBuilder b = new StringBuilder();
        b.append("> Short answer: ").append(shortAnswer(p, v)).append("\n\n");
        b.append("[product: ").append(p.slug()).append("]\n\n");
        b.append("# ").append(pick(v, "Reasons to buy", "Why it is worth it", "What makes it a good pick")).append("\n\n");
        List<String> yes = new ArrayList<>(p.pros().isEmpty() ? p.highlights() : p.pros());
        if (p.discountPercent() != null && p.discountPercent() >= 10) yes.add("It is " + p.discountPercent() + "% below its usual price right now");
        if (p.lowest30d()) yes.add("Today's price is the lowest we have seen in 30 days");
        if (yes.isEmpty() && p.summary() != null) yes.add(stripDot(p.summary()));
        yes.stream().limit(6).forEach(x -> b.append("- ").append(x).append("\n"));
        b.append("\n");
        if (!p.cons().isEmpty()) {
            b.append("# ").append(pick(v, "Reasons to think twice", "What to keep in mind", "Possible deal-breakers")).append("\n\n");
            p.cons().forEach(x -> b.append("- ").append(x).append("\n"));
            b.append("\n");
        }
        whoFor(b, p, v);
        atAGlance(b, p, store);
        priceSection(b, p, store, v);
        b.append("# ").append(pick(v, "Verdict", "So, is it worth it?", "Our answer")).append("\n\n");
        b.append(conclusion(p, store, v)).append("\n");
        alternatives(b, p, related, v);
        disclosure(b, p);
        return draft(BlogKind.WORTH_IT, title, slugFor("is " + p.title() + " worth buying"), summary(p, v), b.toString(), p, store);
    }

    static BlogDraft faqArticle(ProductFacts p, Store store, int v) {
        String name = p.displayName();
        String title = pick(v, name + ": your questions answered", name + " FAQ: price, features and more",
                "Everything you need to know about the " + name);
        StringBuilder b = new StringBuilder();
        b.append(intro(p, store, v)).append("\n\n");
        b.append("[product: ").append(p.slug()).append("]\n\n");
        b.append("# What is the ").append(name).append("?\n\n");
        b.append(p.summary() != null ? sentence(p.summary()) : name + " is " + article(kindNoun(p)) + " " + kindNoun(p)
                + (p.brand() != null ? " from " + p.brand() : "") + ".").append("\n\n");
        if (!p.highlights().isEmpty()) {
            b.append("# What are its main features?\n\n");
            p.highlights().forEach(h -> b.append("- ").append(h).append("\n"));
            b.append("\n");
        }
        for (CatalogPublicService.Spec s : p.specs().stream().limit(6).toList()) {
            b.append("# What is the ").append(s.name().toLowerCase(Locale.ROOT)).append(" of the ").append(name).append("?\n\n")
                    .append(s.value()).append(".\n\n");
        }
        if (p.hasPrice()) {
            b.append("# How much does it cost?\n\n").append(priceSentence(p, store)).append("\n\n");
        }
        b.append("# Where can I buy it?\n\n").append(whereToBuy(p, store)).append("\n\n");
        if (p.digital()) b.append("# How is it delivered?\n\n").append(delivery(p)).append("\n\n");
        if (!p.pros().isEmpty()) {
            b.append("# What is good about it?\n\n");
            p.pros().forEach(x -> b.append("- ").append(x).append("\n"));
            b.append("\n");
        }
        if (!p.cons().isEmpty()) {
            b.append("# Are there any downsides?\n\n");
            p.cons().forEach(x -> b.append("- ").append(x).append("\n"));
            b.append("\n");
        }
        b.append("# Is it worth buying?\n\n").append(shortAnswer(p, v)).append("\n\n");
        b.append("[product: ").append(p.slug()).append("]\n");
        disclosure(b, p);
        return draft(BlogKind.FAQ, title, slugFor(p.title() + " faq"), summary(p, v), b.toString(), p, store);
    }

    static BlogDraft buyingTips(ProductFacts p, List<ProductFacts> related, Store store, int v) {
        String cat = categoryLower(p);
        String title = pick(v, "How to choose " + plural(cat) + ": a simple checklist",
                "Buying " + article(cat) + " " + cat + "? Check these things first",
                plural(capitalize(cat)) + " buying guide " + store.today().getYear());
        StringBuilder b = new StringBuilder();
        b.append(pick(v, "Choosing " + article(cat) + " " + cat + " is easier when you know what to compare. ",
                "There are many " + plural(cat) + " to choose from, and the right one depends on how you will use it. ",
                "Not sure which " + cat + " to get? You are not alone. "))
                .append("This checklist covers what matters, using the ").append(p.displayName())
                .append(" as an example.\n\n");
        b.append("# ").append(pick(v, "What to compare", "The checklist", "Things to check before you buy")).append("\n\n");
        List<String> checks = new ArrayList<>();
        checks.add("Budget: decide your price range first, then compare only within it");
        for (CatalogPublicService.Spec s : p.specs().stream().limit(6).toList()) {
            checks.add(capitalize(s.name()) + ": the " + p.title() + " offers " + s.value());
        }
        if (p.brand() != null) checks.add("Brand and support: " + p.brand() + " is the maker of our example");
        checks.add(p.digital() ? "Delivery and access: check how and when you get it after paying"
                : "Warranty and returns: read the seller's policy before ordering");
        checks.forEach(c -> b.append("- ").append(c).append("\n"));
        b.append("\n");
        b.append("# ").append(pick(v, "Example: " + p.displayName(), "A good example", "One to consider")).append("\n\n");
        b.append(p.summary() != null ? sentence(p.summary()) : p.displayName() + " is a popular choice.").append("\n\n");
        b.append("[product: ").append(p.slug()).append("]\n\n");
        if (!p.highlights().isEmpty()) {
            b.append("Why it stands out:\n\n");
            p.highlights().stream().limit(5).forEach(h -> b.append("- ").append(h).append("\n"));
            b.append("\n");
        }
        if (!related.isEmpty()) {
            b.append("# Other options\n\n");
            b.append("[products: ").append(related.stream().limit(4).map(ProductFacts::slug).collect(Collectors.joining(", "))).append("]\n\n");
        }
        b.append("# Quick tips\n\n");
        b.append("- Compare the features you will actually use, not the longest list\n");
        b.append("- Check recent price history; prices change often\n");
        b.append("- Read reviews from verified buyers when you can\n\n");
        disclosure(b, p);
        return draft(BlogKind.BUYING_TIPS, title, slugFor("how to choose " + plural(cat)), "A simple checklist for choosing "
                + article(cat) + " " + cat + ", with the " + p.displayName() + " as an example.", b.toString(), p, store);
    }

    /** "A vs B (vs C)": side-by-side table, who each one suits, and a pick by price and rating. Needs 2 to 4 products. */
    public static BlogDraft comparison(List<ProductFacts> list, Store store, int v) {
        if (list.size() < 2 || list.size() > 4) throw new IllegalArgumentException("Compare 2 to 4 products");
        String names = list.stream().map(ProductFacts::displayName).collect(Collectors.joining(" vs "));
        String title = pick(v, names + ": which should you buy?", names + " compared", names + ": the differences that matter");
        StringBuilder b = new StringBuilder();
        b.append(pick(v, "Trying to decide between ", "Comparing ", "Stuck choosing between "))
                .append(and(list.stream().map(ProductFacts::displayName).toList()))
                .append("? Here is how they compare on price, features and who each one suits.\n\n");
        b.append("[products: ").append(list.stream().map(ProductFacts::slug).collect(Collectors.joining(", "))).append("]\n\n");
        b.append("# Side by side\n\n");
        b.append("| |").append(list.stream().map(ProductFacts::title).map(ContentWriter::cell).collect(Collectors.joining(" | "))).append(" |\n");
        b.append("|---|").append("---|".repeat(list.size())).append("\n");
        if (list.stream().anyMatch(ProductFacts::hasPrice)) {
            b.append("| Price |").append(list.stream().map(p -> p.hasPrice() ? inr(p.priceMinor()) : "See store").collect(Collectors.joining(" | "))).append(" |\n");
        }
        if (list.stream().anyMatch(p -> p.brand() != null)) {
            b.append("| Brand |").append(list.stream().map(p -> p.brand() == null ? "-" : cell(p.brand())).collect(Collectors.joining(" | "))).append(" |\n");
        }
        if (list.stream().anyMatch(p -> p.expertRating() != null)) {
            b.append("| Our rating |").append(list.stream().map(p -> p.expertRating() == null ? "-" : p.expertRating() + "/10").collect(Collectors.joining(" | "))).append(" |\n");
        }
        LinkedHashSet<String> specNames = new LinkedHashSet<>();
        list.forEach(p -> p.specs().forEach(s -> specNames.add(s.name())));
        specNames.stream().limit(8).forEach(n -> b.append("| ").append(cell(n)).append(" |")
                .append(list.stream().map(p -> p.specs().stream().filter(s -> s.name().equalsIgnoreCase(n)).map(s -> cell(s.value()))
                        .findFirst().orElse("-")).collect(Collectors.joining(" | "))).append(" |\n"));
        b.append("\n");
        for (ProductFacts p : list) {
            b.append("# ").append(p.displayName()).append("\n\n");
            b.append(p.summary() != null ? sentence(p.summary()) : p.displayName() + " is " + article(kindNoun(p)) + " " + kindNoun(p) + ".").append("\n\n");
            List<String> good = p.pros().isEmpty() ? p.highlights() : p.pros();
            if (!good.isEmpty()) {
                b.append("Strengths:\n\n");
                good.stream().limit(4).forEach(x -> b.append("- ").append(x).append("\n"));
                b.append("\n");
            }
            if (!p.cons().isEmpty()) {
                b.append("Weak spots:\n\n");
                p.cons().stream().limit(3).forEach(x -> b.append("- ").append(x).append("\n"));
                b.append("\n");
            }
            b.append("[product: ").append(p.slug()).append("]\n\n");
        }
        b.append("# ").append(pick(v, "Which one should you pick?", "Our recommendation", "The verdict")).append("\n\n");
        ProductFacts cheapest = list.stream().filter(ProductFacts::hasPrice).min(Comparator.comparing(ProductFacts::priceMinor)).orElse(null);
        ProductFacts topRated = list.stream().filter(p -> p.expertRating() != null).max(Comparator.comparing(ProductFacts::expertRating)).orElse(null);
        if (topRated != null) b.append("- Best overall: ").append(topRated.displayName()).append(", with the highest rating (")
                .append(topRated.expertRating()).append("/10)\n");
        if (cheapest != null) b.append("- Best on a budget: ").append(cheapest.displayName()).append(", the lowest price at ")
                .append(inr(cheapest.priceMinor())).append("\n");
        b.append("- Otherwise: pick the one whose strengths match how you will use it\n\n");
        if (list.stream().anyMatch(ProductFacts::affiliate)) disclosure(b, list.stream().filter(ProductFacts::affiliate).findFirst().get());
        String slug = slugFor(list.stream().map(ProductFacts::title).collect(Collectors.joining(" vs ")));
        String summary = "Comparing " + and(list.stream().map(ProductFacts::displayName).toList()) + " on price, features and who each one suits.";
        return draft(BlogKind.COMPARISON, title, slug, summary, b.toString(), list.get(0), store);
    }

    /** "Best {category} in {Month Year}": quick picks, one section per product, comparison table and buying tips. */
    public static BlogDraft roundup(String topic, List<ProductFacts> list, Store store, int v) {
        if (list.size() < 3) throw new IllegalArgumentException("A roundup needs at least 3 products");
        String t = topic == null || topic.isBlank() ? (store.digital() ? "digital products" : "products") : topic.strip();
        String month = store.today().format(MONTH_YEAR);
        String title = pick(v, "Best " + t + " in " + month, list.size() + " best " + t + " to buy in " + month,
                "Top " + list.size() + " " + t + " right now (" + month + ")");
        StringBuilder b = new StringBuilder();
        b.append(pick(v, "We went through our " + t + " and picked the ones worth your money. ",
                "Looking for the best " + t + "? Here are our current top picks. ",
                "These are the " + t + " we would recommend right now. "))
                .append("Prices are shown live, so you always see today's price.\n\n");
        b.append("[products: ").append(list.stream().limit(4).map(ProductFacts::slug).collect(Collectors.joining(", "))).append("]\n\n");
        b.append("# Quick picks\n\n");
        ProductFacts topRated = list.stream().filter(p -> p.expertRating() != null).max(Comparator.comparing(ProductFacts::expertRating)).orElse(list.get(0));
        b.append("- Best overall: ").append(topRated.displayName()).append("\n");
        list.stream().filter(ProductFacts::hasPrice).min(Comparator.comparing(ProductFacts::priceMinor))
                .ifPresent(p -> b.append("- Best value: ").append(p.displayName()).append(" (").append(inr(p.priceMinor())).append(")\n"));
        list.stream().filter(p -> p.discountPercent() != null && p.discountPercent() > 0).max(Comparator.comparing(ProductFacts::discountPercent))
                .ifPresent(p -> b.append("- Biggest discount: ").append(p.displayName()).append(" (").append(p.discountPercent()).append("% off)\n"));
        b.append("\n");
        int i = 1;
        for (ProductFacts p : list) {
            b.append("# ").append(i++).append(". ").append(p.displayName()).append("\n\n");
            b.append(p.summary() != null ? sentence(p.summary()) : p.displayName() + " is one of our favourite " + t + ".").append("\n\n");
            p.highlights().stream().limit(4).forEach(h -> b.append("- ").append(h).append("\n"));
            if (!p.highlights().isEmpty()) b.append("\n");
            b.append("[product: ").append(p.slug()).append("]\n\n");
        }
        if (list.stream().anyMatch(ProductFacts::hasPrice)) {
            b.append("# Price comparison\n\n| Product | Price | Discount |\n|---|---|---|\n");
            list.forEach(p -> b.append("| ").append(cell(p.displayName())).append(" | ").append(p.hasPrice() ? inr(p.priceMinor()) : "See store")
                    .append(" | ").append(p.discountPercent() == null || p.discountPercent() == 0 ? "-" : p.discountPercent() + "%").append(" |\n"));
            b.append("\n");
        }
        b.append("# How we choose\n\n").append("We look at features, price, how each product compares with similar ones")
                .append(" and, where available, buyer reviews. We update this list when prices or products change.\n\n");
        if (list.stream().anyMatch(ProductFacts::affiliate)) disclosure(b, list.stream().filter(ProductFacts::affiliate).findFirst().get());
        String summary = "Our current top " + t + ", with quick picks for best overall, best value and biggest discount.";
        return draft(BlogKind.ROUNDUP, title, slugFor("best " + t + " " + month), summary, b.toString(), list.get(0), store);
    }

    // ================================================================== web stories

    public record StoryPage(String headline, String body, String cta) {
    }

    public record StoryDraft(String title, String description, List<StoryPage> pages) {
    }

    /** A 3 to 6 page Web Story: intro, up to three highlights or pros, a "good to know" con, then price and verdict. */
    public static StoryDraft story(ProductFacts p, Store store, int v) {
        List<StoryPage> pages = new ArrayList<>();
        String cta = store.digital() ? "Get it" : "See the deal";
        pages.add(new StoryPage(cut(p.displayName(), 90),
                cut(p.summary() != null ? sentence(p.summary()) : pick(v, "New at ", "Just added at ", "Now at ") + store.name() + ".", 250), cta));
        List<String> good = p.highlights().isEmpty() ? p.pros() : p.highlights();
        String[] heads = {pick(v, "Why it stands out", "What you get", "The highlights"), pick(v, "Also good", "Plus", "And there is more"),
                pick(v, "One more thing", "Worth knowing", "Bonus")};
        for (int i = 0; i < Math.min(3, good.size()); i++) pages.add(new StoryPage(heads[i], cut(sentence(good.get(i)), 250), cta));
        if (!p.cons().isEmpty()) pages.add(new StoryPage("Good to know", cut(sentence(p.cons().get(0)), 250), cta));
        if (pages.size() < 3 && !p.specs().isEmpty()) {
            String specs = p.specs().stream().limit(3).map(s -> s.name() + ": " + s.value()).collect(Collectors.joining(" · "));
            pages.add(new StoryPage("Key specs", cut(specs, 250), cta));
        }
        String closing = p.verdict() != null ? sentence(p.verdict())
                : p.hasPrice() ? priceLine(p) : pick(v, "Tap below for details and the latest price.", "See the full details on our site.", "Swipe up to learn more.");
        String head = p.hasPrice() ? (p.discountPercent() != null && p.discountPercent() >= 5 ? p.discountPercent() + "% off today" : "Now " + inr(p.priceMinor()))
                : pick(v, "Our verdict", "Should you get it?", "The bottom line");
        pages.add(new StoryPage(head, cut(closing, 250), cta));
        while (pages.size() < 3) pages.add(new StoryPage(pick(v + pages.size(), "Find it at " + store.name(), "Available now", "Take a look"),
                "Tap below for details and the latest price.", cta));
        String title = cut(pick(v, p.title(), "Meet the " + p.title(), p.title() + ": quick look"), 90);
        String description = cut(p.summary() != null ? sentence(p.summary()) : p.displayName() + " at " + store.name() + ".", 300);
        return new StoryDraft(title, description, pages);
    }

    // ================================================================== newsletters

    public static EmailDraft email(EmailKind kind, List<ProductFacts> list, String topic, Store store, int v) {
        if (list.isEmpty()) throw new IllegalArgumentException("Choose at least one product");
        String date = store.today().format(DAY_MONTH);
        return switch (kind) {
            case LAUNCH -> launch(list.get(0), store, v, date);
            case NEW_ARRIVALS -> listEmail(kind, "New arrivals " + date,
                    pick(v, "Just in at {store}: " + list.size() + " new picks", "New this week at {store}", "Fresh arrivals for you, {name}"),
                    pick(v, "Here is what just arrived at {store}:", "We have added some new favourites:", "These are new in the store:"),
                    list, store);
            case TOP_DEALS -> listEmail(kind, "Top deals " + date,
                    pick(v, "This week's best deals at {store}", "{name}, these deals are worth a look", "Top price drops at {store}"),
                    pick(v, "These are the biggest savings in the store right now:", "Prices on these just dropped:", "Our best-value picks right now:"),
                    list.stream().sorted(Comparator.comparing((ProductFacts p) -> p.discountPercent() == null ? 0 : p.discountPercent()).reversed()).toList(),
                    store);
            case COLLECTION -> {
                String t = topic == null || topic.isBlank() ? "our favourites" : topic.strip();
                yield listEmail(kind, "Collection: " + t + " " + date,
                        pick(v, "Our picks: " + t, "{name}, explore " + t, "The " + t + " edit"),
                        pick(v, "We put together our favourite " + t + ":", "A short list of " + t + " we recommend:", "Hand-picked " + t + " for you:"),
                        list, store);
            }
        };
    }

    private static EmailDraft launch(ProductFacts p, Store store, int v, String date) {
        String subject = pick(v, "New at {store}: " + p.title(), "Just arrived: " + p.title(), "{name}, meet the " + p.title());
        StringBuilder b = new StringBuilder("Hi {name},\n\n");
        b.append(pick(v, "We have just added something new to {store}:", "There is a new arrival we think you will like:",
                "Say hello to our newest pick:")).append("\n\n");
        b.append(p.displayName().toUpperCase(Locale.ROOT)).append("\n");
        if (p.summary() != null) b.append(sentence(p.summary())).append("\n");
        b.append("\n");
        p.highlights().stream().limit(4).forEach(h -> b.append("- ").append(h).append("\n"));
        if (!p.highlights().isEmpty()) b.append("\n");
        if (p.hasPrice()) b.append(priceLine(p)).append("\n\n");
        b.append(pick(v, "See it here: ", "Take a look: ", "Details and price: ")).append(p.productUrl()).append("\n\n");
        b.append("Happy shopping,\n{store}\n");
        return new EmailDraft(EmailKind.LAUNCH.name(), "New: " + cut(p.title(), 90) + " " + date, subject, b.toString());
    }

    private static EmailDraft listEmail(EmailKind kind, String name, String subject, String opener, List<ProductFacts> list, Store store) {
        StringBuilder b = new StringBuilder("Hi {name},\n\n").append(opener).append("\n\n");
        int i = 1;
        for (ProductFacts p : list.stream().limit(8).toList()) {
            b.append(i++).append(". ").append(p.displayName()).append("\n");
            if (p.summary() != null) b.append("   ").append(cut(sentence(p.summary()), 160)).append("\n");
            if (p.hasPrice()) b.append("   ").append(priceLine(p)).append("\n");
            b.append("   ").append(p.productUrl()).append("\n\n");
        }
        b.append("See everything at {link}\n\nHappy shopping,\n{store}\n");
        return new EmailDraft(kind.name(), cut(name, 120), subject, b.toString());
    }

    // ================================================================== social posts

    public static List<SocialPost> social(ProductFacts p, Store store, int v) {
        String url = p.productUrl();
        String tags = hashtags(p, store, 8);
        String price = p.hasPrice() ? priceLine(p) : null;
        String hook = pick(v, "New find: ", "Worth a look: ", "Just in: ") + p.displayName();
        List<String> hl = p.highlights().stream().limit(3).toList();
        List<SocialPost> out = new ArrayList<>();

        StringBuilder ig = new StringBuilder(hook).append("\n\n");
        if (p.summary() != null) ig.append(sentence(p.summary())).append("\n\n");
        hl.forEach(h -> ig.append("✔ ").append(h).append("\n"));
        if (!hl.isEmpty()) ig.append("\n");
        if (price != null) ig.append(price).append("\n");
        ig.append(pick(v, "Tap the link in our bio to see it on ", "Find it on ", "Shop it on ")).append(store.name()).append("\n\n").append(tags);
        out.add(new SocialPost("instagram", "Instagram caption", cut(ig.toString(), 2200), null, 2200));

        StringBuilder fb = new StringBuilder(hook).append("\n\n");
        if (p.summary() != null) fb.append(sentence(p.summary())).append("\n\n");
        hl.forEach(h -> fb.append("• ").append(h).append("\n"));
        if (!hl.isEmpty()) fb.append("\n");
        if (price != null) fb.append(price).append("\n");
        fb.append(url);
        out.add(new SocialPost("facebook", "Facebook post", fb.toString(),
                "https://www.facebook.com/sharer/sharer.php?u=" + enc(url), 5000));

        String pinTitle = cut(p.displayName() + (p.discountPercent() != null && p.discountPercent() > 0 ? " – " + p.discountPercent() + "% off" : ""), 100);
        StringBuilder pin = new StringBuilder();
        pin.append(p.summary() != null ? sentence(p.summary()) : p.displayName() + ".").append(" ");
        if (!hl.isEmpty()) pin.append(String.join(". ", hl)).append(". ");
        if (price != null) pin.append(price).append(". ");
        pin.append(hashtags(p, store, 5));
        String pinText = cut(pin.toString(), 500);
        out.add(new SocialPost("pinterest", "Pinterest pin", pinTitle + "\n\n" + pinText,
                "https://www.pinterest.com/pin/create/button/?url=" + enc(url)
                        + (p.imageUrl() != null ? "&media=" + enc(p.imageUrl()) : "") + "&description=" + enc(pinTitle + " – " + pinText), 500));

        StringBuilder li = new StringBuilder(pick(v, "We have added the " + p.displayName() + " to " + store.name() + ".",
                "New on " + store.name() + ": the " + p.displayName() + ".", "Introducing the " + p.displayName() + " on " + store.name() + "."))
                .append("\n\n");
        if (p.summary() != null) li.append(sentence(p.summary())).append("\n\n");
        if (!hl.isEmpty()) {
            li.append("What stands out:\n");
            hl.forEach(h -> li.append("→ ").append(h).append("\n"));
            li.append("\n");
        }
        li.append(url).append("\n\n").append(hashtags(p, store, 3));
        out.add(new SocialPost("linkedin", "LinkedIn post", li.toString(),
                "https://www.linkedin.com/sharing/share-offsite/?url=" + enc(url), 3000));

        // X counts every link as 23 characters.
        String xBody = hook + (price != null ? " – " + price : "") + "\n" + hashtags(p, store, 2);
        if (xBody.length() > 280 - 24) xBody = cut(hook + (price != null ? " – " + price : ""), 280 - 24);
        out.add(new SocialPost("x", "X (Twitter) post", xBody + "\n" + url,
                "https://twitter.com/intent/tweet?text=" + enc(xBody) + "&url=" + enc(url), 280));

        StringBuilder wa = new StringBuilder("*").append(p.displayName()).append("*\n");
        if (p.summary() != null) wa.append(cut(sentence(p.summary()), 200)).append("\n");
        hl.forEach(h -> wa.append("✅ ").append(h).append("\n"));
        if (price != null) wa.append("💰 ").append(price).append("\n");
        wa.append("👉 ").append(url);
        out.add(new SocialPost("whatsapp", "WhatsApp message", wa.toString(), "https://wa.me/?text=" + enc(wa.toString()), 4000));

        StringBuilder tg = new StringBuilder(hook).append("\n");
        if (price != null) tg.append(price).append("\n");
        hl.forEach(h -> tg.append("• ").append(h).append("\n"));
        tg.append(url);
        out.add(new SocialPost("telegram", "Telegram post", tg.toString(),
                "https://t.me/share/url?url=" + enc(url) + "&text=" + enc(hook), 4096));
        return out;
    }

    /** Hashtags from the brand, category and title words, plus a store-wide tag; letters and digits only. */
    static String hashtags(ProductFacts p, Store store, int max) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        if (p.brand() != null) tags.add(tag(p.brand()));
        if (p.category() != null) tags.add(tag(p.category()));
        for (String w : p.title().split("[^\\p{L}\\p{N}]+")) {
            String lw = w.toLowerCase(Locale.ROOT);
            if (w.length() >= 3 && !STOP.contains(lw) && !lw.chars().allMatch(Character::isDigit)) tags.add(tag(w));
        }
        tags.add(store.digital() ? "#DigitalDeals" : "#ShoppingDeals");
        if (p.discountPercent() != null && p.discountPercent() >= 10) tags.add("#Sale");
        tags.add(tag(store.name()));
        return tags.stream().filter(t -> t.length() > 2).limit(max).collect(Collectors.joining(" "));
    }

    private static String tag(String words) {
        StringBuilder sb = new StringBuilder("#");
        for (String w : words.split("[^\\p{L}\\p{N}]+")) {
            if (w.isEmpty()) continue;
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.length() > 31 ? sb.substring(0, 31) : sb.toString();
    }

    // ================================================================== shared sections

    private static String intro(ProductFacts p, Store store, int v) {
        String name = p.displayName();
        String kind = kindNoun(p);
        StringBuilder s = new StringBuilder(pick(v,
                "Thinking about the " + name + "? ",
                "The " + name + " is " + article(kind) + " " + kind + " that gets a lot of attention. ",
                "If you are shopping for " + article(kind) + " " + kind + ", the " + name + " is likely on your list. "));
        if (p.summary() != null) s.append(sentence(p.summary())).append(" ");
        s.append(pick(v, "Here is what it offers, what to watch out for and whether it is right for you.",
                "We look at its features, price and who it suits best.",
                "Below we cover the key facts so you can decide quickly."));
        return s.toString();
    }

    private static void atAGlance(StringBuilder b, ProductFacts p, Store store) {
        List<String[]> rows = new ArrayList<>();
        if (p.brand() != null) rows.add(new String[]{"Brand", p.brand()});
        if (p.category() != null) rows.add(new String[]{"Category", p.category()});
        if (p.hasPrice()) rows.add(new String[]{"Price", inr(p.priceMinor()) + (p.mrpMinor() != null ? " (MRP " + inr(p.mrpMinor()) + ")" : "")});
        if (p.expertRating() != null) rows.add(new String[]{"Our rating", p.expertRating() + "/10"});
        p.specs().stream().limit(8).forEach(s -> rows.add(new String[]{s.name(), s.value()}));
        if (p.affiliate() && p.merchantName() != null) rows.add(new String[]{"Sold by", p.merchantName()});
        if (rows.size() < 2) return;
        b.append("# At a glance\n\n| Detail | ").append(cell(p.title())).append(" |\n|---|---|\n");
        rows.forEach(r -> b.append("| ").append(cell(r[0])).append(" | ").append(cell(r[1])).append(" |\n"));
        b.append("\n");
    }

    private static void prosCons(StringBuilder b, ProductFacts p, int v) {
        if (!p.pros().isEmpty()) {
            b.append("# ").append(pick(v, "What we like", "Pros", "The good")).append("\n\n");
            p.pros().forEach(x -> b.append("- ").append(x).append("\n"));
            b.append("\n");
        }
        if (!p.cons().isEmpty()) {
            b.append("# ").append(pick(v, "What could be better", "Cons", "The not-so-good")).append("\n\n");
            p.cons().forEach(x -> b.append("- ").append(x).append("\n"));
            b.append("\n");
        }
    }

    private static void whoFor(StringBuilder b, ProductFacts p, int v) {
        List<String> good = p.pros().isEmpty() ? p.highlights() : p.pros();
        if (good.isEmpty()) return;
        b.append("# ").append(pick(v, "Who is it for?", "Who should buy it?", "Is it right for you?")).append("\n\n");
        b.append("It is a good fit if these matter to you: ").append(and(good.stream().limit(3).map(ContentWriter::lowerFirst).toList())).append(".");
        if (!p.cons().isEmpty()) b.append(" Look elsewhere if ").append(lowerFirst(p.cons().get(0))).append(" is a deal-breaker.");
        b.append("\n\n");
    }

    private static void priceSection(StringBuilder b, ProductFacts p, Store store, int v) {
        b.append("# ").append(pick(v, "Price and where to buy", "How much does it cost?", "Price")).append("\n\n");
        if (p.hasPrice()) b.append(priceSentence(p, store)).append(" ");
        b.append(whereToBuy(p, store)).append("\n\n");
    }

    private static void faq(StringBuilder b, ProductFacts p, Store store, int v) {
        b.append("# ").append(pick(v, "FAQs", "Frequently asked questions", "Common questions")).append("\n\n");
        if (p.hasPrice()) b.append("## How much is the ").append(p.displayName()).append("?\n\n").append(priceSentence(p, store)).append("\n\n");
        b.append("## Where can I buy it?\n\n").append(whereToBuy(p, store)).append("\n\n");
        if (p.digital()) b.append("## How do I get it after buying?\n\n").append(delivery(p)).append("\n\n");
        if (!p.highlights().isEmpty()) b.append("## What are its best features?\n\n")
                .append(capitalize(and(p.highlights().stream().limit(3).map(ContentWriter::lowerFirst).toList()))).append(".\n\n");
        if (!p.cons().isEmpty()) b.append("## Does it have any drawbacks?\n\n")
                .append(capitalize(and(p.cons().stream().limit(2).map(ContentWriter::lowerFirst).toList()))).append(".\n\n");
    }

    private static void alternatives(StringBuilder b, ProductFacts p, List<ProductFacts> related, int v) {
        List<ProductFacts> others = related.stream().filter(r -> !r.id().equals(p.id())).limit(3).toList();
        if (others.size() < 2) return;
        b.append("\n# ").append(pick(v, "Alternatives to consider", "Similar options", "You might also like")).append("\n\n");
        b.append("[products: ").append(others.stream().map(ProductFacts::slug).collect(Collectors.joining(", "))).append("]\n");
    }

    private static void disclosure(StringBuilder b, ProductFacts p) {
        if (!p.affiliate()) return;
        b.append("\n> Disclosure: some links on this page are partner links. If you buy through them we may earn a small")
                .append(" commission at no extra cost to you.\n");
    }

    private static String conclusion(ProductFacts p, Store store, int v) {
        String name = p.displayName();
        StringBuilder s = new StringBuilder();
        if (p.verdict() != null) s.append(sentence(p.verdict())).append(" ");
        else if (!p.pros().isEmpty()) s.append("The ").append(name).append(" stands out for ").append(and(p.pros().stream().limit(2).map(ContentWriter::lowerFirst).toList())).append(". ");
        else if (!p.highlights().isEmpty()) s.append("The ").append(name).append(" offers ").append(and(p.highlights().stream().limit(2).map(ContentWriter::lowerFirst).toList())).append(". ");
        if (p.discountPercent() != null && p.discountPercent() >= 10) s.append("At ").append(p.discountPercent()).append("% off, now is a good time to buy. ");
        s.append(pick(v, "Check today's price and details below.", "See the latest price and offers on the product page.",
                "Tap below for the current price."));
        return s.toString();
    }

    private static String shortAnswer(ProductFacts p, int v) {
        String name = p.displayName();
        List<String> good = p.pros().isEmpty() ? p.highlights() : p.pros();
        StringBuilder s = new StringBuilder();
        if (!good.isEmpty()) {
            s.append(pick(v, "Yes, for most buyers. ", "For the right buyer, yes. ", "In most cases, yes. "))
                    .append("The ").append(name).append(" offers ").append(and(good.stream().limit(2).map(ContentWriter::lowerFirst).toList())).append(".");
        } else {
            s.append("It depends on what you need. Compare its features below with how you plan to use it.");
        }
        if (!p.cons().isEmpty()) s.append(" The main thing to keep in mind: ").append(lowerFirst(p.cons().get(0))).append(".");
        return s.toString();
    }

    private static String priceSentence(ProductFacts p, Store store) {
        StringBuilder s = new StringBuilder("On ").append(store.today().format(DAY_MONTH)).append(" ").append(store.today().getYear())
                .append(" it costs ").append(inr(p.priceMinor()));
        if (p.mrpMinor() != null) s.append(", down from ").append(inr(p.mrpMinor()));
        if (p.discountPercent() != null && p.discountPercent() > 0) s.append(" (").append(p.discountPercent()).append("% off)");
        s.append(".");
        if (p.lowest30d()) s.append(" That is the lowest price in the last 30 days.");
        s.append(" Prices change, so the product card shows the current price.");
        return s.toString();
    }

    private static String whereToBuy(ProductFacts p, Store store) {
        if (p.affiliate() && p.merchantName() != null) {
            return "It is available from " + p.merchantName() + ". Use the button on this page to go straight to the offer.";
        }
        return "You can buy it directly from " + store.name() + " with secure online payment.";
    }

    private static String delivery(ProductFacts p) {
        if (p.affiliate()) return "Delivery and access are handled by " + (p.merchantName() == null ? "the partner store" : p.merchantName()) + ".";
        if ("LICENSE_KEY".equals(p.deliveryKind())) return "Your licence key appears in your account library right after payment and is emailed to you.";
        return "It is delivered instantly: after payment it appears in your account library, ready to download.";
    }

    static String priceLine(ProductFacts p) {
        StringBuilder s = new StringBuilder("Now ").append(inr(p.priceMinor()));
        if (p.mrpMinor() != null) s.append(" (MRP ").append(inr(p.mrpMinor())).append(")");
        if (p.discountPercent() != null && p.discountPercent() > 0) s.append(" – ").append(p.discountPercent()).append("% off");
        return s.toString();
    }

    private static String summary(ProductFacts p, int v) {
        if (p.summary() != null) return cut(sentence(p.summary()), 300);
        return pick(v, "Features, pros and cons, price and FAQs for the " + p.displayName() + ".",
                "Everything you need to know before buying the " + p.displayName() + ".",
                "A quick, honest look at the " + p.displayName() + ".");
    }

    private static BlogDraft draft(BlogKind kind, String title, String slug, String summary, String body, ProductFacts p, Store store) {
        String metaTitle = cut(title, 60);
        String desc = summary + (p.hasPrice() && kind != BlogKind.ROUNDUP && kind != BlogKind.COMPARISON ? " Price: " + inr(p.priceMinor()) + "." : "");
        return new BlogDraft(kind.name(), cut(title, 200), slug, cut(summary, 400), body, metaTitle, cut(desc, 160));
    }

    // ================================================================== text helpers

    static String pick(int variant, String... options) {
        return options[Math.floorMod(variant, options.length)];
    }

    static String inr(long minor) {
        NumberFormat f = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"));
        f.setCurrency(Currency.getInstance("INR"));
        if (minor % 100 == 0) {
            f.setMaximumFractionDigits(0);
            f.setMinimumFractionDigits(0);
        }
        return f.format(BigDecimal.valueOf(minor, 2));
    }

    static String and(List<String> items) {
        if (items.isEmpty()) return "";
        if (items.size() == 1) return items.get(0);
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
    }

    static String sentence(String s) {
        String t = s.strip();
        if (t.isEmpty()) return t;
        char last = t.charAt(t.length() - 1);
        return last == '.' || last == '!' || last == '?' ? t : t + ".";
    }

    private static String stripDot(String s) {
        String t = s.strip();
        return t.endsWith(".") ? t.substring(0, t.length() - 1) : t;
    }

    static String lowerFirst(String s) {
        if (s == null || s.length() < 2) return s;
        // Keep acronyms and brand names ("USB-C", "iPhone") as they are.
        if (Character.isUpperCase(s.charAt(1)) || !Character.isUpperCase(s.charAt(0))) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String cut(String v, int max) {
        if (v == null) return null;
        String t = v.strip();
        if (t.length() <= max) return t;
        int space = t.lastIndexOf(' ', max - 1);
        return (space > max / 2 ? t.substring(0, space) : t.substring(0, max - 1)).strip() + "…";
    }

    private static String cell(String s) {
        return s.replace("|", "/").replace("\n", " ").strip();
    }

    private static String kindNoun(ProductFacts p) {
        if (p.category() != null) return singular(p.category().toLowerCase(Locale.ROOT));
        return p.digital() ? "digital product" : "product";
    }

    private static String categoryLower(ProductFacts p) {
        return kindNoun(p);
    }

    static String singular(String w) {
        if (w.endsWith("ies") && w.length() > 4) return w.substring(0, w.length() - 3) + "y";
        if (w.endsWith("sses") || w.endsWith("shes") || w.endsWith("ches")) return w.substring(0, w.length() - 2);
        if (w.endsWith("s") && !w.endsWith("ss") && w.length() > 3) return w.substring(0, w.length() - 1);
        return w;
    }

    static String plural(String w) {
        if (w.endsWith("s")) return w;
        if (w.endsWith("y") && w.length() > 2 && "aeiou".indexOf(w.charAt(w.length() - 2)) < 0) return w.substring(0, w.length() - 1) + "ies";
        if (w.endsWith("ch") || w.endsWith("sh") || w.endsWith("x")) return w + "es";
        return w + "s";
    }

    private static String article(String word) {
        return word != null && !word.isEmpty() && "aeiouAEIOU".indexOf(word.charAt(0)) >= 0 ? "an" : "a";
    }

    static String slugFor(String text) {
        String s = text.toLowerCase(Locale.ROOT).replace("₹", "rs ").replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (s.length() > 110) s = s.substring(0, 110).replaceAll("-+$", "");
        return s.isEmpty() ? "post" : s;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
