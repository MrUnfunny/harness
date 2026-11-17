package com.harness.tools.impl;

import com.harness.config.AppConfig;
import com.harness.tools.Tool;
import com.harness.tools.ToolCall;
import com.harness.tools.ToolContext;
import com.harness.tools.ToolResult;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

public final class WebFetchTool implements Tool {
    private static final AppConfig CFG = AppConfig.get();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(CFG.httpConnectTimeout())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override public String name() { return "web_fetch"; }
    @Override public String description() {
        return "Read the content of a webpage by fetching its URL. Use this AFTER web_search has given you a URL to investigate. "
                + "HTML is stripped to plain text. Truncated at " + CFG.webFetchMaxChars() + " chars. "
                + "This is the ONLY tool for reading URLs — never pass URLs to file_read.";
    }
    @Override public List<ArgSpec> argSpecs() {
        return List.of(new ArgSpec("url", "Absolute http(s) URL.", true));
    }
    @Override public boolean requiresApproval() { return false; }

    @Override
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        String url = call.arg("url").trim();
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            return ToolResult.error("Only http(s) URLs are allowed");
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(CFG.httpRequestTimeout())
                    .header("User-Agent", CFG.webFetchUserAgent())
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            String ctype = resp.headers().firstValue("content-type").orElse("").toLowerCase();
            String body = resp.body();

            String text = ctype.contains("html") ? htmlToText(body) : body;
            int maxChars = CFG.webFetchMaxChars();
            if (text.length() > maxChars) {
                text = text.substring(0, maxChars) + "\n[truncated at " + maxChars + " chars]";
            }
            return ToolResult.ok("HTTP " + resp.statusCode() + " (" + ctype + ")\n\n" + text);
        } catch (IOException | InterruptedException e) {
            return ToolResult.error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    static String htmlToText(String html) {
        String s = html;
        s = s.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        s = s.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        s = s.replaceAll("(?is)<!--.*?-->", " ");
        s = s.replaceAll("(?is)<br\\s*/?>", "\n");
        s = s.replaceAll("(?is)</(p|div|li|h[1-6]|tr)>", "\n");
        s = s.replaceAll("<[^>]+>", "");
        s = s.replace("&nbsp;", " ")
             .replace("&amp;", "&")
             .replace("&lt;", "<")
             .replace("&gt;", ">")
             .replace("&quot;", "\"")
             .replace("&#39;", "'");
        s = s.replaceAll("[ \\t]+", " ");
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }
}
