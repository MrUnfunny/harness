package com.harness.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.agent.Message;
import com.harness.config.AppConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

public final class OllamaClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AppConfig CFG = AppConfig.get();

    public record ChatResult(String content, int inputTokens, int outputTokens, long durationMs) {}

    private final HttpClient http;
    private final String host;
    private final String model;
    private final double temperature;

    public OllamaClient(String host, String model, double temperature) {
        this.host = host.replaceAll("/+$", "");
        this.model = model;
        this.temperature = temperature;
        this.http = HttpClient.newBuilder()
                .connectTimeout(CFG.ollamaConnectTimeout())
                .build();
    }

    /** Calls /api/chat (non-streaming) and returns assistant content plus token/time stats. */
    public ChatResult chat(List<Message> history) throws IOException, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        ObjectNode options = body.putObject("options");
        options.put("temperature", temperature);

        ArrayNode messages = body.putArray("messages");
        for (Message m : history) {
            ObjectNode n = messages.addObject();
            n.put("role", m.role());
            n.put("content", m.content());
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(host + "/api/chat"))
                .timeout(CFG.ollamaRequestTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();

        int maxAttempts = CFG.ollamaRetryAttempts();
        long start = System.nanoTime();
        IOException lastErr = null;
        long backoffMs = CFG.ollamaRetryInitialBackoffMs();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 != 2) {
                    throw new IOException("Ollama returned " + resp.statusCode() + ": " + resp.body());
                }
                JsonNode root = JSON.readTree(resp.body());
                String content = root.path("message").path("content").asText("");
                int inTok  = root.path("prompt_eval_count").asInt(0);
                int outTok = root.path("eval_count").asInt(0);
                long durMs = (System.nanoTime() - start) / 1_000_000L;
                return new ChatResult(content, inTok, outTok, durMs);
            } catch (IOException e) {
                lastErr = e;
                if (attempt < maxAttempts) {
                    Thread.sleep(backoffMs);
                    backoffMs *= 2;
                }
            }
        }
        throw new IOException("Ollama request failed after " + maxAttempts + " attempts: "
                + (lastErr == null ? "unknown" : lastErr.getClass().getSimpleName() + " - " + lastErr.getMessage()),
                lastErr);
    }

    public String model() { return model; }
    public String host()  { return host; }
}
