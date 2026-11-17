package com.harness.config;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Properties;

/**
 * Typed, immutable view of the tuning knobs defined in {@code /application.properties}.
 *
 * <p>Resolution order for each key: {@code System.getProperty} → properties file → hard-coded default.
 * This means any value can be overridden at launch with {@code -Dkey=value} without touching the file.
 *
 * <p>Access via the {@link #get()} singleton — the file is read once at first use.
 */
public final class AppConfig {

    private static final String RESOURCE_PATH = "/application.properties";

    // Ollama / model defaults (CLI flags override)
    private final String ollamaHost;
    private final String ollamaModel;
    private final double ollamaTemperature;

    // Agent loop
    private final String agentDefaultMode;
    private final int agentMaxRounds;
    private final int agentCompactCharBudget;
    private final int stockMinSearches;
    private final int stockMinFetches;
    private final int stockMaxNudges;

    // Ollama timeouts / retries
    private final Duration ollamaConnectTimeout;
    private final Duration ollamaRequestTimeout;
    private final int ollamaRetryAttempts;
    private final long ollamaRetryInitialBackoffMs;

    // Generic HTTP
    private final Duration httpConnectTimeout;
    private final Duration httpRequestTimeout;

    // web_fetch
    private final int webFetchMaxChars;
    private final String webFetchUserAgent;

    // web_search
    private final int webSearchDefaultMaxResults;
    private final int webSearchMaxResultsCap;
    private final long webSearchRetryBaseMs;
    private final long webSearchRetryJitterMs;
    private final URI webSearchHtmlEndpoint;
    private final URI webSearchLiteEndpoint;
    private final String webSearchUserAgentHtml;
    private final String webSearchUserAgentLite;

    // file_read
    private final int fileReadDefaultMaxLines;

    // glob
    private final int globMaxResults;

    // grep
    private final int grepDefaultMaxResults;
    private final long grepMaxFileBytes;

    // shell
    private final int shellDefaultTimeoutSec;
    private final int shellMaxTimeoutSec;
    private final int shellMaxStreamBytes;

    private AppConfig(Properties p) {
        this.ollamaHost                = stringOr(p, "ollama.host", "http://localhost:11434");
        this.ollamaModel               = stringOr(p, "ollama.model", "gemma4:e4b");
        this.ollamaTemperature         = doubleOr(p, "ollama.temperature", 0.2);

        this.agentDefaultMode          = stringOr(p, "agent.defaultMode", "coding");
        this.agentMaxRounds            = intOr(p, "agent.maxRounds", 25);
        this.agentCompactCharBudget    = intOr(p, "agent.compactCharBudget", 32_000);
        this.stockMinSearches          = intOr(p, "agent.stockResearch.minSearches", 2);
        this.stockMinFetches           = intOr(p, "agent.stockResearch.minFetches", 3);
        this.stockMaxNudges            = intOr(p, "agent.stockResearch.maxNudges", 2);

        this.ollamaConnectTimeout      = Duration.ofSeconds(intOr(p, "ollama.connectTimeoutSec", 10));
        this.ollamaRequestTimeout      = Duration.ofMinutes(intOr(p, "ollama.requestTimeoutMin", 5));
        this.ollamaRetryAttempts       = intOr(p, "ollama.retryAttempts", 3);
        this.ollamaRetryInitialBackoffMs = longOr(p, "ollama.retryInitialBackoffMs", 500);

        this.httpConnectTimeout        = Duration.ofSeconds(intOr(p, "http.connectTimeoutSec", 10));
        this.httpRequestTimeout        = Duration.ofSeconds(intOr(p, "http.requestTimeoutSec", 20));

        this.webFetchMaxChars          = intOr(p, "webFetch.maxChars", 50_000);
        this.webFetchUserAgent         = stringOr(p, "webFetch.userAgent", "harness/0.1");

        this.webSearchDefaultMaxResults = intOr(p, "webSearch.defaultMaxResults", 10);
        this.webSearchMaxResultsCap    = intOr(p, "webSearch.maxResultsCap", 20);
        this.webSearchRetryBaseMs      = longOr(p, "webSearch.retryBaseMs", 1500);
        this.webSearchRetryJitterMs    = longOr(p, "webSearch.retryJitterMs", 500);
        this.webSearchHtmlEndpoint     = URI.create(stringOr(p, "webSearch.htmlEndpoint", "https://html.duckduckgo.com/html/"));
        this.webSearchLiteEndpoint     = URI.create(stringOr(p, "webSearch.liteEndpoint", "https://lite.duckduckgo.com/lite/"));
        this.webSearchUserAgentHtml    = stringOr(p, "webSearch.userAgentHtml",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        this.webSearchUserAgentLite    = stringOr(p, "webSearch.userAgentLite",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:124.0) Gecko/20100101 Firefox/124.0");

        this.fileReadDefaultMaxLines   = intOr(p, "fileRead.defaultMaxLines", 2000);

        this.globMaxResults            = intOr(p, "glob.maxResults", 500);

        this.grepDefaultMaxResults     = intOr(p, "grep.defaultMaxResults", 200);
        this.grepMaxFileBytes          = longOr(p, "grep.maxFileBytes", 2_000_000L);

        this.shellDefaultTimeoutSec    = intOr(p, "shell.defaultTimeoutSec", 30);
        this.shellMaxTimeoutSec        = intOr(p, "shell.maxTimeoutSec", 300);
        this.shellMaxStreamBytes       = intOr(p, "shell.maxStreamBytes", 64 * 1024);
    }

    // Lazy-initialized singleton. Held by a static holder so the file is read on first use.
    private static final class Holder {
        static final AppConfig INSTANCE = new AppConfig(loadProperties());
    }

    public static AppConfig get() {
        return Holder.INSTANCE;
    }

    private static Properties loadProperties() {
        Properties p = new Properties();
        try (InputStream in = AppConfig.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in != null) p.load(in);
        } catch (IOException e) {
            System.err.println("[AppConfig] could not read " + RESOURCE_PATH + " — using defaults: " + e.getMessage());
        }
        return p;
    }

    // ---- accessors ---------------------------------------------------------

    public String ollamaHost()             { return ollamaHost; }
    public String ollamaModel()            { return ollamaModel; }
    public double ollamaTemperature()      { return ollamaTemperature; }

    public String agentDefaultMode()       { return agentDefaultMode; }
    public int agentMaxRounds()            { return agentMaxRounds; }
    public int agentCompactCharBudget()    { return agentCompactCharBudget; }
    public int stockMinSearches()          { return stockMinSearches; }
    public int stockMinFetches()           { return stockMinFetches; }
    public int stockMaxNudges()            { return stockMaxNudges; }

    public Duration ollamaConnectTimeout() { return ollamaConnectTimeout; }
    public Duration ollamaRequestTimeout() { return ollamaRequestTimeout; }
    public int ollamaRetryAttempts()       { return ollamaRetryAttempts; }
    public long ollamaRetryInitialBackoffMs() { return ollamaRetryInitialBackoffMs; }

    public Duration httpConnectTimeout()   { return httpConnectTimeout; }
    public Duration httpRequestTimeout()   { return httpRequestTimeout; }

    public int webFetchMaxChars()          { return webFetchMaxChars; }
    public String webFetchUserAgent()      { return webFetchUserAgent; }

    public int webSearchDefaultMaxResults() { return webSearchDefaultMaxResults; }
    public int webSearchMaxResultsCap()    { return webSearchMaxResultsCap; }
    public long webSearchRetryBaseMs()     { return webSearchRetryBaseMs; }
    public long webSearchRetryJitterMs()   { return webSearchRetryJitterMs; }
    public URI webSearchHtmlEndpoint()     { return webSearchHtmlEndpoint; }
    public URI webSearchLiteEndpoint()     { return webSearchLiteEndpoint; }
    public String webSearchUserAgentHtml() { return webSearchUserAgentHtml; }
    public String webSearchUserAgentLite() { return webSearchUserAgentLite; }

    public int fileReadDefaultMaxLines()   { return fileReadDefaultMaxLines; }

    public int globMaxResults()            { return globMaxResults; }

    public int grepDefaultMaxResults()     { return grepDefaultMaxResults; }
    public long grepMaxFileBytes()         { return grepMaxFileBytes; }

    public int shellDefaultTimeoutSec()    { return shellDefaultTimeoutSec; }
    public int shellMaxTimeoutSec()        { return shellMaxTimeoutSec; }
    public int shellMaxStreamBytes()       { return shellMaxStreamBytes; }

    // ---- resolution helpers -----------------------------------------------

    private static String stringOr(Properties p, String key, String fallback) {
        String sys = System.getProperty(key);
        if (sys != null && !sys.isBlank()) return sys;
        String v = p.getProperty(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static int intOr(Properties p, String key, int fallback) {
        String v = stringOr(p, key, null);
        if (v == null) return fallback;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) {
            System.err.println("[AppConfig] invalid int for " + key + "='" + v + "', using default " + fallback);
            return fallback;
        }
    }

    private static long longOr(Properties p, String key, long fallback) {
        String v = stringOr(p, key, null);
        if (v == null) return fallback;
        try { return Long.parseLong(v.trim()); }
        catch (NumberFormatException e) {
            System.err.println("[AppConfig] invalid long for " + key + "='" + v + "', using default " + fallback);
            return fallback;
        }
    }

    private static double doubleOr(Properties p, String key, double fallback) {
        String v = stringOr(p, key, null);
        if (v == null) return fallback;
        try { return Double.parseDouble(v.trim()); }
        catch (NumberFormatException e) {
            System.err.println("[AppConfig] invalid double for " + key + "='" + v + "', using default " + fallback);
            return fallback;
        }
    }
}
