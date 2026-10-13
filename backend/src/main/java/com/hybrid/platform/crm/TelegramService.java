package com.hybrid.platform.crm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Posts plain-text messages to a Telegram channel or group through the free Telegram Bot API. The bot token is
 * stored encrypted and never logged.
 */
@Service
public class TelegramService {

    private static final Logger log = LoggerFactory.getLogger(TelegramService.class);
    static final Pattern TOKEN = Pattern.compile("^\\d{5,15}:[A-Za-z0-9_-]{30,64}$");
    static final Pattern CHAT = Pattern.compile("^(@[A-Za-z][A-Za-z0-9_]{4,31}|-?\\d{5,20})$");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json;
    private final String apiBase;

    public TelegramService(ObjectMapper json, @Value("${app.telegram.api-base:https://api.telegram.org}") String apiBase) {
        this.json = json;
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
    }

    /** Sends the message; returns null on success, otherwise Telegram's (or the network's) explanation. */
    public String send(String token, String chatId, String text) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("chat_id", chatId);
            body.put("text", text.length() > 4000 ? text.substring(0, 3997) + "..." : text);
            body.put("disable_web_page_preview", false);
            HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase + "/bot" + token + "/sendMessage"))
                    .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8)).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode reply = res.body() == null || res.body().isBlank() ? null : json.readTree(res.body());
            if (reply != null && reply.path("ok").asBoolean(false)) return null;
            String reason = reply == null ? "HTTP " + res.statusCode() : reply.path("description").asText("HTTP " + res.statusCode());
            log.warn("Telegram rejected a message for chat {}: {}", chatId, reason);
            return reason;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted";
        } catch (Exception e) {
            log.warn("Telegram message to {} failed: {}", chatId, e.getClass().getSimpleName());
            return "Could not reach Telegram (" + e.getClass().getSimpleName() + ")";
        }
    }
}
