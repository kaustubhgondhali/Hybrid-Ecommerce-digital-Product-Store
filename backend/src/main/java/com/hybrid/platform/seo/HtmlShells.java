package com.hybrid.platform.seo;

import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads the static HTML page shells (e.g. store/product.html) from the configured static locations so the server
 * can inject SEO tags and crawlable content. Shells from the classpath are cached; file-system shells (dev profile)
 * are re-read so edits show immediately.
 */
@Component
public class HtmlShells {

    private final ResourceLoader loader;
    private final String[] locations;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public HtmlShells(ResourceLoader loader, WebProperties web) {
        this.loader = loader;
        this.locations = web.getResources().getStaticLocations();
    }

    public String load(String path) {
        String cached = cache.get(path);
        if (cached != null) return cached;
        for (String location : locations) {
            String base = location.endsWith("/") ? location : location + "/";
            Resource r = loader.getResource(base + path);
            if (r.exists() && r.isReadable()) {
                try {
                    String html = r.getContentAsString(StandardCharsets.UTF_8);
                    if (base.startsWith("classpath:")) cache.put(path, html);
                    return html;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        throw new IllegalStateException("Page shell not found: " + path);
    }
}
