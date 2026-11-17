package com.harness.tools.impl;

import com.harness.config.AppConfig;
import com.harness.tools.Tool;
import com.harness.tools.ToolCall;
import com.harness.tools.ToolContext;
import com.harness.tools.ToolResult;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WebSearchTool implements Tool {
    private static final AppConfig CFG = AppConfig.get();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(CFG.httpConnectTimeout())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // html.duckduckgo.com layout.
    private static final Pattern HTML_TITLE = Pattern.compile(
            "<a\\s+[^>]*class=\"result__a\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern HTML_SNIPPET = Pattern.compile(
            "<a\\s+[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern HTML_URL = Pattern.compile(
            "<a\\s+[^>]*class=\"result__url\"[^>]*>(.*?)</a>", Pattern.DOTALL);

    // lite.duckduckgo.com layout: title inside <a class="result-link" href=...>, snippet in <td class="result-snippet">.
    private static final Pattern LITE_LINK = Pattern.compile(
            "<a\\s+[^>]*class=\"result-link\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern LITE_SNIPPET = Pattern.compile(
            "<td\\s+[^>]*class=\"result-snippet\"[^>]*>(.*?)</td>", Pattern.DOTALL);

    private record Hit(String title, String url, String snippet) {}

    @Override public String name() { return "web_search"; }
    @Override public String description() {
        return "Search the web via DuckDuckGo. Returns the top titles, URLs, and snippets. "
                + "Use this when you need to discover pages; follow up with web_fetch to read the content of a specific URL.";
    }
    @Override public List<Tool.ArgSpec> argSpecs() {
        return List.of(
                new Tool.ArgSpec("query", "Search query.", true),
                new Tool.ArgSpec("max_results",
                        "Max results to return (default " + CFG.webSearchDefaultMaxResults()
                                + ", max " + CFG.webSearchMaxResultsCap() + ").", false));
    }
    @Override public boolean requiresApproval() { return false; }

    @Override
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        String query = call.arg("query").trim();
        if (query.isEmpty()) return ToolResult.error("query is required");

        int max = clamp(parseIntOrDefault(call.arg("max_results", ""), CFG.webSearchDefaultMaxResults()),
                1, CFG.webSearchMaxResultsCap());

        try {
            List<Hit> hits = searchWithRetryAndFallback(query);
            if (hits.isEmpty()) {
                return ToolResult.ok("(no results parsed — DuckDuckGo blocking both html and lite endpoints; retry later)");
            }
            return ToolResult.ok(formatHits(hits, max));
        } catch (IOException | InterruptedException e) {
            return ToolResult.error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Try html.duckduckgo.com first; on empty results, wait briefly and retry once; on still-empty,
     * fall back to the lighter lite.duckduckgo.com layout with a different UA.
     */
    private static List<Hit> searchWithRetryAndFallback(String query) throws IOException, InterruptedException {
        List<Hit> hits = searchHtml(query);
        if (!hits.isEmpty()) return hits;

        sleepWithJitter(CFG.webSearchRetryBaseMs(), CFG.webSearchRetryJitterMs());
        hits = searchHtml(query);
        if (!hits.isEmpty()) return hits;

        return searchLite(query);
    }

    private static List<Hit> searchHtml(String query) throws IOException, InterruptedException {
        String html = postForm(CFG.webSearchHtmlEndpoint(), CFG.webSearchUserAgentHtml(), query);
        if (html == null) return List.of();

        List<String> titles = findAll(HTML_TITLE, html, 1);
        List<String> snippets = findAll(HTML_SNIPPET, html, 1);
        List<String> urls = findAll(HTML_URL, html, 1);
        int n = Math.min(titles.size(), Math.min(snippets.size(), urls.size()));

        List<Hit> hits = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            hits.add(new Hit(clean(titles.get(i)), clean(urls.get(i)), clean(snippets.get(i))));
        }
        return hits;
    }

    private static List<Hit> searchLite(String query) throws IOException, InterruptedException {
        String html = postForm(CFG.webSearchLiteEndpoint(), CFG.webSearchUserAgentLite(), query);
        if (html == null) return List.of();

        List<String> snippets = findAll(LITE_SNIPPET, html, 1);
        Matcher m = LITE_LINK.matcher(html);
        List<Hit> hits = new ArrayList<>();
        int idx = 0;
        while (m.find()) {
            String url = unwrapDdgRedirect(m.group(1));
            String title = clean(m.group(2));
            String snippet = idx < snippets.size() ? clean(snippets.get(idx)) : "";
            hits.add(new Hit(title, url, snippet));
            idx++;
        }
        return hits;
    }

    /** POST a {@code q=<query>} form; returns the response body, or null on non-2xx. */
    private static String postForm(URI uri, String userAgent, String query) throws IOException, InterruptedException {
        String form = "q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(CFG.httpRequestTimeout())
                .header("User-Agent", userAgent)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.statusCode() / 100 == 2 ? resp.body() : null;
    }

    private static String formatHits(List<Hit> hits, int max) {
        int n = Math.min(max, hits.size());
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < n; i++) {
            Hit h = hits.get(i);
            String url = h.url().startsWith("http") ? h.url() : "https://" + h.url();
            out.append(i + 1).append(". ").append(h.title()).append("\n");
            out.append("   ").append(url).append("\n");
            if (!h.snippet().isEmpty()) out.append("   ").append(h.snippet()).append("\n");
            out.append("\n");
        }
        return out.toString();
    }

    /** lite.duckduckgo.com wraps outbound links as /l/?uddg=<encoded-url>&rut=... — unwrap if present. */
    static String unwrapDdgRedirect(String href) {
        if (href == null) return "";
        String h = href.startsWith("//") ? "https:" + href : href;
        int idx = h.indexOf("uddg=");
        if (idx < 0) return h;
        int valueStart = idx + "uddg=".length();
        int valueEnd = h.indexOf('&', valueStart);
        String encoded = valueEnd < 0 ? h.substring(valueStart) : h.substring(valueStart, valueEnd);
        try {
            return java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return h;
        }
    }

    private static String clean(String html) {
        return WebFetchTool.htmlToText(html).trim();
    }

    private static void sleepWithJitter(long baseMs, long jitterMs) throws InterruptedException {
        Thread.sleep(baseMs + ThreadLocalRandom.current().nextLong(jitterMs));
    }

    private static List<String> findAll(Pattern p, String s, int group) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(s);
        while (m.find()) out.add(m.group(group));
        return out;
    }

    private static int parseIntOrDefault(String s, int fallback) {
        if (s == null || s.isBlank()) return fallback;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    private static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }
}
