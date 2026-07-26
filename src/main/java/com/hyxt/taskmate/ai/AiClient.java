package com.hyxt.taskmate.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hyxt.taskmate.config.ModConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** OpenAI 兼容 Chat Completions 客户端(异步)。 */
public final class AiClient {

    /** AI 回复 + token 用量 */
    public record ChatResult(String content, long promptTokens, long completionTokens) {}

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /** 运行期发现服务商不支持 response_format 后置为 true,不再重试 JSON 模式 */
    private static volatile boolean jsonModeUnsupported = false;

    private AiClient() {}

    public static CompletableFuture<ChatResult> chat(String systemPrompt, List<Conversation.Msg> history, ModConfig cfg) {
        boolean jsonMode = cfg.useJsonMode && !jsonModeUnsupported;
        return doChat(systemPrompt, history, cfg, jsonMode)
                .exceptionallyCompose(err -> {
                    // 部分服务商不支持 response_format:去掉后重试一次并记住
                    if (jsonMode && mentionsResponseFormat(err)) {
                        jsonModeUnsupported = true;
                        return doChat(systemPrompt, history, cfg, false);
                    }
                    return CompletableFuture.failedFuture(err);
                });
    }

    private static boolean mentionsResponseFormat(Throwable err) {
        Throwable c = err;
        while (c != null) {
            String m = c.getMessage();
            if (m != null && m.toLowerCase().contains("response_format")) return true;
            c = c.getCause() == c ? null : c.getCause();
        }
        return false;
    }

    private static CompletableFuture<ChatResult> doChat(String systemPrompt, List<Conversation.Msg> history,
                                                        ModConfig cfg, boolean jsonMode) {
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model);
        body.addProperty("temperature", cfg.temperature);
        body.addProperty("max_tokens", cfg.maxTokens);
        if (jsonMode) {
            JsonObject rf = new JsonObject();
            rf.addProperty("type", "json_object");
            body.add("response_format", rf);
        }

        JsonArray messages = new JsonArray();
        messages.add(msg("system", systemPrompt));
        for (Conversation.Msg m : history) {
            messages.add(msg(m.role(), m.content()));
        }
        body.add("messages", messages);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint(cfg.baseUrl)))
                .timeout(Duration.ofSeconds(Math.max(30, cfg.requestTimeoutSeconds)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + cfg.apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(AiClient::extract);
    }

    static String endpoint(String baseUrl) {
        String url = baseUrl == null ? "" : baseUrl.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (!url.endsWith("/chat/completions")) url = url + "/chat/completions";
        return url;
    }

    private static JsonObject msg(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    private static ChatResult extract(HttpResponse<String> resp) {
        String bodyStr = resp.body();
        JsonObject root;
        try {
            root = JsonParser.parseString(bodyStr).getAsJsonObject();
        } catch (Exception e) {
            throw new RuntimeException("HTTP " + resp.statusCode() + ",响应不是 JSON: " + snippet(bodyStr));
        }
        if (root.has("error") && !root.get("error").isJsonNull()) {
            JsonObject err = root.getAsJsonObject("error");
            String m = err.has("message") ? err.get("message").getAsString() : err.toString();
            throw new RuntimeException("API 错误: " + m);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + snippet(bodyStr));
        }
        String content;
        try {
            content = root.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
        } catch (Exception e) {
            throw new RuntimeException("无法从响应中取出内容: " + snippet(bodyStr));
        }
        long prompt = 0, completion = 0;
        try {
            if (root.has("usage") && root.get("usage").isJsonObject()) {
                JsonObject usage = root.getAsJsonObject("usage");
                if (usage.has("prompt_tokens")) prompt = usage.get("prompt_tokens").getAsLong();
                if (usage.has("completion_tokens")) completion = usage.get("completion_tokens").getAsLong();
            }
        } catch (Exception ignored) {
        }
        return new ChatResult(content, prompt, completion);
    }

    private static String snippet(String s) {
        if (s == null) return "(空)";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
