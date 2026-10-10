package com.hybrid.platform.research;

import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.research.AffiliatePlatformCatalog.CategoryPlaybook;
import com.hybrid.platform.research.AffiliatePlatformCatalog.Platform;
import com.hybrid.platform.research.AffiliatePlatformCatalog.ProductSeed;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Fast, offline affiliate product research: scores category playbooks against
 * pasted Google research / review notes and existing catalog titles.
 * Does not call Google or scrape the web.
 */
public final class AffiliateResearchAnalyzer {

    public record CatalogSnapshot(List<String> productTitles, List<String> categoryNames,
                                  List<String> connectedMerchantHosts) {
    }

    public record Request(Storefront storefront, String niche, String researchNotes,
                          List<String> categoryFocus, int maxPerCategory) {
    }

    public record Signal(String type, String text, String detail) {
    }

    public record SearchQuery(String query, String purpose, String googleUrl) {
    }

    public record ProductSuggestion(String title, String why, int fitScore, String priority,
                                    List<String> platforms, List<String> tags, boolean alreadyInCatalog,
                                    String matchedCatalogTitle) {
    }

    public record CategorySuggestion(String categoryId, String categoryName, String blurb, int opportunityScore,
                                     List<ProductSuggestion> products, List<SearchQuery> searches,
                                     List<String> platforms) {
    }

    public record PlatformFit(String id, String name, String program, String region, String joinUrl,
                              int fitScore, String reason, boolean merchantConnected, List<String> strengths) {
    }

    public record Report(String storefront, String niche, int opportunityScore, String grade,
                         String summary, List<Signal> signals, List<PlatformFit> platforms,
                         List<CategorySuggestion> categories, List<SearchQuery> nextSearches,
                         List<String> actions, long analyzedInMs) {
    }

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}'-]{1,40}");
    private static final Pattern PRICE = Pattern.compile("(?:rs\\.?|inr|₹)\\s*([\\d,]+)|\\b(\\d{3,6})\\s*(?:rs|inr)?\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> STOP = Set.of(
            "the", "and", "for", "with", "from", "this", "that", "have", "has", "was", "were", "are", "you",
            "your", "our", "their", "they", "them", "but", "not", "all", "any", "can", "will", "just", "about",
            "into", "over", "under", "than", "then", "also", "very", "more", "most", "some", "such", "only",
            "other", "out", "when", "what", "which", "who", "how", "why", "best", "good", "great",
            "review", "reviews", "rating", "ratings", "stars", "star", "product", "products", "india", "indian",
            "buy", "bought", "using", "used", "use", "like", "liked", "really", "after", "before", "month",
            "months", "year", "years", "day", "days", "one", "two", "three", "https", "http", "www", "com");

    private static final List<String> POSITIVE = List.of(
            "worth", "recommend", "excellent", "amazing", "durable", "value", "love", "reliable", "accurate",
            "comfortable", "fast", "quality", "premium", "perfect", "impressive");
    private static final List<String> NEGATIVE = List.of(
            "waste", "broke", "broken", "fake", "poor", "cheap", "disappoint", "return", "refund", "scam",
            "battery drain", "overheat", "uncomfortable", "flimsy", "delay", "late");

    private AffiliateResearchAnalyzer() {
    }

    public static Report analyze(Request req, CatalogSnapshot catalog) {
        long start = System.nanoTime();
        String niche = normalize(req.niche());
        String notes = normalize(req.researchNotes());
        String corpus = (niche + " " + notes).toLowerCase(Locale.ROOT);
        Set<String> tokens = tokens(corpus);
        Set<String> focus = req.categoryFocus() == null ? Set.of()
                : req.categoryFocus().stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        int max = Math.clamp(req.maxPerCategory() <= 0 ? 4 : req.maxPerCategory(), 1, 8);

        List<Signal> signals = extractSignals(niche, notes, tokens);
        Map<String, Platform> platformById = AffiliatePlatformCatalog.platforms().stream()
                .collect(Collectors.toMap(Platform::id, p -> p, (a, b) -> a, LinkedHashMap::new));

        Set<String> existingNorm = catalog.productTitles().stream()
                .map(AffiliateResearchAnalyzer::normKey).collect(Collectors.toCollection(HashSet::new));

        List<CategoryPlaybook> books = AffiliatePlatformCatalog.playbooks(req.storefront()).stream()
                .filter(b -> focus.isEmpty() || focus.contains(b.id()) || focus.contains(b.name().toLowerCase(Locale.ROOT)))
                .toList();

        List<CategorySuggestion> categories = new ArrayList<>();
        Map<String, Integer> platformHits = new LinkedHashMap<>();
        List<SearchQuery> globalSearches = new ArrayList<>();

        for (CategoryPlaybook book : books) {
            int catBoost = categoryBoost(book, niche, tokens, corpus);
            List<ProductSuggestion> products = new ArrayList<>();
            for (ProductSeed seed : book.products()) {
                int fit = scoreSeed(seed, book, niche, tokens, corpus, catBoost);
                Match match = findCatalogMatch(seed, existingNorm, catalog.productTitles());
                String priority = fit >= 85 ? "HIGH" : fit >= 70 ? "MEDIUM" : "WATCH";
                products.add(new ProductSuggestion(seed.title(), seed.why(), fit, priority,
                        book.platformIds(), seed.tags(), match.found(), match.title()));
                for (String pid : book.platformIds()) {
                    platformHits.merge(pid, fit / 10, Integer::sum);
                }
            }
            products.sort(Comparator.comparingInt(ProductSuggestion::fitScore).reversed());
            if (products.size() > max) products = new ArrayList<>(products.subList(0, max));

            int opp = products.isEmpty() ? catBoost
                    : (int) Math.round(products.stream().mapToInt(ProductSuggestion::fitScore).average().orElse(catBoost));
            List<SearchQuery> searches = buildSearches(book, niche, notes, tokens);
            globalSearches.addAll(searches.stream().limit(2).toList());
            categories.add(new CategorySuggestion(book.id(), book.name(), book.blurb(), opp, products, searches,
                    book.platformIds()));
        }
        categories.sort(Comparator.comparingInt(CategorySuggestion::opportunityScore).reversed());

        List<PlatformFit> platforms = scorePlatforms(req.storefront(), platformById, platformHits,
                catalog.connectedMerchantHosts(), corpus, tokens);
        int opportunity = categories.isEmpty() ? 40
                : (int) Math.round(categories.stream().limit(4).mapToInt(CategorySuggestion::opportunityScore).average().orElse(40));
        opportunity = Math.clamp(opportunity + Math.min(10, signals.size()), 0, 100);

        List<SearchQuery> next = dedupeSearches(globalSearches, niche, req.storefront());
        List<String> actions = buildActions(categories, platforms, signals, catalog);
        String summary = buildSummary(req.storefront(), niche, opportunity, categories, platforms, notes);
        long ms = (System.nanoTime() - start) / 1_000_000L;
        return new Report(req.storefront().path(), niche.isEmpty() ? null : niche.trim(), opportunity,
                grade(opportunity), summary, signals, platforms, categories, next, actions, ms);
    }

    private static List<PlatformFit> scorePlatforms(Storefront sf, Map<String, Platform> byId,
                                                    Map<String, Integer> hits, List<String> connectedHosts,
                                                    String corpus, Set<String> tokens) {
        Set<String> hosts = connectedHosts.stream().map(h -> h == null ? "" : h.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        List<PlatformFit> out = new ArrayList<>();
        for (Platform p : AffiliatePlatformCatalog.platforms()) {
            if (!p.storefronts().contains(sf)) continue;
            int base = 45 + Math.min(35, hits.getOrDefault(p.id(), 0));
            boolean connected = hosts.stream().anyMatch(h -> h.contains(p.id().replace("-", ""))
                    || p.name().toLowerCase(Locale.ROOT).contains(h)
                    || h.contains(p.name().toLowerCase(Locale.ROOT).split("\\s+")[0]));
            // Host heuristics for common merchants
            if (!connected) {
                connected = hosts.stream().anyMatch(h ->
                        (p.id().equals("amazon-in") && h.contains("amazon"))
                                || (p.id().equals("flipkart") && h.contains("flipkart"))
                                || (p.id().equals("myntra") && h.contains("myntra"))
                                || (p.id().equals("nykaa") && h.contains("nykaa"))
                                || (p.id().equals("ajio") && h.contains("ajio"))
                                || (p.id().equals("croma") && h.contains("croma")));
            }
            if (connected) base += 12;
            if (p.region().equals("IN") && (corpus.contains("india") || tokens.contains("india"))) base += 5;
            if (p.region().equals("GLOBAL") && sf == Storefront.DIGITAL) base += 5;
            base = Math.clamp(base, 0, 100);
            String reason = connected
                    ? "You already have a connected merchant domain that matches this program."
                    : "Affiliate-capable platform for " + sf.path() + " — join before publishing partner links.";
            out.add(new PlatformFit(p.id(), p.name(), p.program(), p.region(), p.joinUrl(), base, reason,
                    connected, p.strengths()));
        }
        out.sort(Comparator.comparingInt(PlatformFit::fitScore).reversed());
        return out;
    }

    private static int categoryBoost(CategoryPlaybook book, String niche, Set<String> tokens, String corpus) {
        int score = 50;
        String name = book.name().toLowerCase(Locale.ROOT);
        if (!niche.isEmpty() && (name.contains(niche.toLowerCase(Locale.ROOT))
                || niche.toLowerCase(Locale.ROOT).contains(book.id().replace('-', ' ')))) {
            score += 25;
        }
        for (String t : book.id().split("-")) {
            if (tokens.contains(t)) score += 6;
        }
        for (ProductSeed s : book.products()) {
            for (String tag : s.tags()) {
                if (tokens.contains(tag) || corpus.contains(tag)) {
                    score += 3;
                    break;
                }
            }
        }
        return Math.clamp(score, 0, 95);
    }

    private static int scoreSeed(ProductSeed seed, CategoryPlaybook book, String niche, Set<String> tokens,
                                 String corpus, int catBoost) {
        int score = (seed.demandScore() * 55 + seed.commissionHint() * 25) / 100;
        score = (score * 70 + catBoost * 30) / 100;
        for (String tag : seed.tags()) {
            if (tokens.contains(tag) || corpus.contains(tag)) score += 8;
        }
        String titleLow = seed.title().toLowerCase(Locale.ROOT);
        for (String t : tokens) {
            if (t.length() >= 4 && titleLow.contains(t)) score += 4;
        }
        if (!niche.isEmpty() && titleLow.contains(niche.toLowerCase(Locale.ROOT))) score += 10;
        return Math.clamp(score, 20, 99);
    }

    private record Match(boolean found, String title) {
    }

    private static Match findCatalogMatch(ProductSeed seed, Set<String> existingNorm, List<String> titles) {
        String key = normKey(seed.title());
        if (existingNorm.contains(key)) {
            return new Match(true, titles.stream().filter(t -> normKey(t).equals(key)).findFirst().orElse(seed.title()));
        }
        for (String tag : seed.tags()) {
            String tagKey = normKey(tag);
            for (String title : titles) {
                String tn = normKey(title);
                if (tn.contains(tagKey) && tagKey.length() >= 4) {
                    return new Match(true, title);
                }
            }
        }
        // fuzzy: share significant words
        Set<String> seedWords = tokens(seed.title().toLowerCase(Locale.ROOT));
        for (String title : titles) {
            Set<String> tw = tokens(title.toLowerCase(Locale.ROOT));
            long overlap = seedWords.stream().filter(w -> w.length() >= 4 && tw.contains(w)).count();
            if (overlap >= 2) return new Match(true, title);
        }
        return new Match(false, null);
    }

    private static List<Signal> extractSignals(String niche, String notes, Set<String> tokens) {
        List<Signal> signals = new ArrayList<>();
        if (!niche.isEmpty()) {
            signals.add(new Signal("NICHE", niche.trim(), "Focus niche you asked the tool to research."));
        }
        if (notes.isBlank()) {
            signals.add(new Signal("INPUT", "No research notes pasted",
                    "Paste Google review excerpts, roundups, or forum threads for sharper scoring."));
            return signals;
        }
        String low = notes.toLowerCase(Locale.ROOT);
        for (String p : POSITIVE) {
            if (low.contains(p)) {
                signals.add(new Signal("PRAISE", p, "Positive language found in pasted research."));
                break;
            }
        }
        for (String n : NEGATIVE) {
            if (low.contains(n)) {
                signals.add(new Signal("COMPLAINT", n, "Objection or quality risk mentioned — address in your listing copy."));
                break;
            }
        }
        Matcher prices = PRICE.matcher(notes);
        if (prices.find()) {
            String raw = prices.group(1) != null ? prices.group(1) : prices.group(2);
            signals.add(new Signal("PRICE", "≈ ₹" + raw.replace(",", ""),
                    "Price cue detected — use it when writing compare-at / value copy."));
        }
        // top content keywords (length >= 5)
        Map<String, Integer> freq = new LinkedHashMap<>();
        for (String t : tokens) {
            if (t.length() < 5 || STOP.contains(t)) continue;
            freq.merge(t, 1, Integer::sum);
        }
        freq.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(6)
                .forEach(e -> signals.add(new Signal("KEYWORD", e.getKey(),
                        "Repeated " + e.getValue() + "× in your research notes — strong search/review theme.")));
        return signals;
    }

    private static List<SearchQuery> buildSearches(CategoryPlaybook book, String niche, String notes,
                                                   Set<String> tokens) {
        LinkedHashSet<String> qs = new LinkedHashSet<>();
        if (!niche.isEmpty()) {
            qs.add(niche.trim() + " best " + book.name().toLowerCase(Locale.ROOT) + " India 2026");
            qs.add(niche.trim() + " review site:youtube.com");
            qs.add(niche.trim() + " affiliate program");
        }
        for (String q : book.googleQueries()) {
            qs.add(q);
            if (qs.size() >= 6) break;
        }
        // inject a keyword from notes if present
        tokens.stream().filter(t -> t.length() >= 5 && !STOP.contains(t)).limit(2)
                .forEach(t -> qs.add("best " + t + " India review 2026"));
        List<SearchQuery> out = new ArrayList<>();
        int i = 0;
        for (String q : qs) {
            String purpose = i == 0 ? "Category demand scan"
                    : i == 1 ? "Review / content angle"
                    : i == 2 ? "Confirm affiliate program exists"
                    : "Deepen product shortlist";
            out.add(new SearchQuery(q, purpose, googleUrl(q)));
            if (++i >= 5) break;
        }
        return out;
    }

    private static List<SearchQuery> dedupeSearches(List<SearchQuery> in, String niche, Storefront sf) {
        LinkedHashMap<String, SearchQuery> map = new LinkedHashMap<>();
        if (!niche.isEmpty()) {
            String q1 = niche.trim() + " best products India 2026 review";
            map.put(q1.toLowerCase(Locale.ROOT), new SearchQuery(q1, "Start here — Google research roundup", googleUrl(q1)));
            String q2 = niche.trim() + " " + (sf == Storefront.DIGITAL ? "SaaS OR course" : "buy") + " affiliate program";
            map.put(q2.toLowerCase(Locale.ROOT), new SearchQuery(q2, "Verify affiliate availability", googleUrl(q2)));
        }
        for (SearchQuery s : in) {
            map.putIfAbsent(s.query().toLowerCase(Locale.ROOT), s);
            if (map.size() >= 10) break;
        }
        return new ArrayList<>(map.values());
    }

    private static List<String> buildActions(List<CategorySuggestion> categories, List<PlatformFit> platforms,
                                             List<Signal> signals, CatalogSnapshot catalog) {
        List<String> actions = new ArrayList<>();
        categories.stream().limit(2).forEach(c -> {
            c.products().stream().filter(p -> !p.alreadyInCatalog()).findFirst().ifPresent(p ->
                    actions.add("Create an affiliate draft in “" + c.categoryName() + "”: " + p.title()
                            + " (fit " + p.fitScore() + ")."));
        });
        platforms.stream().filter(p -> !p.merchantConnected()).limit(2).forEach(p ->
                actions.add("Join " + p.program() + " (" + p.name() + "), then paste partner links on products."));
        long gaps = categories.stream().flatMap(c -> c.products().stream()).filter(p -> !p.alreadyInCatalog()).count();
        if (gaps > 0) {
            actions.add(gaps + " suggested SKUs are not in your catalog yet — prioritize HIGH fit scores.");
        }
        if (catalog.productTitles().isEmpty()) {
            actions.add("Catalog is empty — publish 5–8 HIGH suggestions first, each with a confirmed affiliate link.");
        }
        boolean hasComplaint = signals.stream().anyMatch(s -> "COMPLAINT".equals(s.type()));
        if (hasComplaint) {
            actions.add("Your notes mention quality complaints — pick brands with strong return policies and say so in the disclosure.");
        }
        if (actions.isEmpty()) {
            actions.add("Re-run with pasted Google review excerpts for sharper keyword and price signals.");
        }
        return actions;
    }

    private static String buildSummary(Storefront sf, String niche, int score, List<CategorySuggestion> cats,
                                       List<PlatformFit> platforms, String notes) {
        String topCat = cats.isEmpty() ? "your niche" : cats.getFirst().categoryName();
        String topPlat = platforms.isEmpty() ? "an affiliate network" : platforms.getFirst().name();
        String focus = niche.isEmpty() ? "this " + sf.path() + " storefront" : "“" + niche.trim() + "”";
        String notesBit = notes.isBlank()
                ? "No research notes were pasted, so scores use the affiliate playbook defaults."
                : "Scores are boosted from the research notes you pasted.";
        return "Opportunity " + score + "/100 for " + focus + ". Lead with " + topCat
                + " via " + topPlat + ". " + notesBit
                + " Only platforms with affiliate programs are listed — buy links stay partner-side.";
    }

    private static String grade(int score) {
        if (score >= 85) return "Strong";
        if (score >= 70) return "Good";
        if (score >= 55) return "Fair";
        return "Thin";
    }

    private static String googleUrl(String q) {
        return "https://www.google.com/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8);
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim();
    }

    private static String normKey(String s) {
        return tokens(s.toLowerCase(Locale.ROOT)).stream().sorted().collect(Collectors.joining(" "));
    }

    private static Set<String> tokens(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String w = m.group().toLowerCase(Locale.ROOT);
            if (!STOP.contains(w)) out.add(w);
        }
        return out;
    }
}
