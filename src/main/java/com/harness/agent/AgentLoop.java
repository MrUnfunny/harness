package com.harness.agent;

import com.harness.approval.ApprovalManager;
import com.harness.config.AppConfig;
import com.harness.ollama.OllamaClient;
import com.harness.persistence.SessionStore;
import com.harness.tools.Tool;
import com.harness.tools.ToolCall;
import com.harness.tools.ToolContext;
import com.harness.tools.ToolRegistry;
import com.harness.tools.ToolResult;
import com.harness.ui.Ansi;
import com.harness.ui.Spinner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class AgentLoop {
    private static final AppConfig CFG = AppConfig.get();

    private final OllamaClient ollama;
    private final ToolRegistry registry;
    private final ApprovalManager approvals;
    private final SessionStore store;
    private final ToolContext toolCtx;
    private final List<Message> history;
    private final Mode mode;

    public AgentLoop(OllamaClient ollama,
                     ToolRegistry registry,
                     ApprovalManager approvals,
                     SessionStore store,
                     ToolContext toolCtx,
                     List<Message> history,
                     Mode mode) {
        this.ollama = ollama;
        this.registry = registry;
        this.approvals = approvals;
        this.store = store;
        this.toolCtx = toolCtx;
        this.history = history;
        this.mode = mode;
    }

    public List<Message> history() { return history; }

    public void runTurn(String userInput) throws IOException, InterruptedException {
        approvals.resetTurn();

        Message userMsg = Message.user(userInput);
        history.add(userMsg);
        store.append(userMsg);

        int totalIn = 0, totalOut = 0;
        long totalMs = 0;
        int webSearches = 0, webFetches = 0, nudges = 0;

        for (int round = 1; round <= CFG.agentMaxRounds(); round++) {
            maybeCompact();

            OllamaClient.ChatResult result;
            Spinner spinner = new Spinner(Ansi.dim("thinking (" + ollama.model() + ")"));
            spinner.start();
            try {
                try {
                    result = ollama.chat(history);
                } finally {
                    spinner.stop();
                }
            } catch (IOException e) {
                System.err.println();
                System.err.println(Ansi.red("[ollama error] ") + e.getMessage());
                Throwable cause = e.getCause();
                while (cause != null) {
                    System.err.println(Ansi.dim("  caused by: ") + cause.getClass().getName() + " - " + cause.getMessage());
                    cause = cause.getCause();
                }
                System.err.println(Ansi.dim("--- stack trace ---"));
                e.printStackTrace(System.err);
                return;
            }

            totalIn += result.inputTokens();
            totalOut += result.outputTokens();
            totalMs += result.durationMs();

            String assistantText = result.content();
            Message assistantMsg = Message.assistant(assistantText);
            history.add(assistantMsg);
            store.append(assistantMsg);

            System.out.println(Ansi.gray(String.format(
                    "  round %d  |  %d in  |  %d out  |  %s",
                    round, result.inputTokens(), result.outputTokens(),
                    Spinner.formatElapsed(result.durationMs()))));

            List<ToolCall> calls = ToolCallParser.parse(assistantText);

            if (calls.isEmpty()) {
                String nudge = maybeStockResearchNudge(webSearches, webFetches, nudges);
                if (nudge != null) {
                    nudges++;
                    System.out.println();
                    System.out.println(Ansi.yellow("[auto-nudge] ") + Ansi.dim("research minimums not met; asking model to continue"));
                    Message nudgeMsg = Message.user(nudge);
                    history.add(nudgeMsg);
                    store.append(nudgeMsg);
                    continue;
                }

                String prose = ToolCallParser.stripToolCalls(assistantText);
                System.out.println();
                System.out.println(prose.isEmpty() ? Ansi.dim("(no response)") : prose);
                System.out.println();
                System.out.println(Ansi.gray(String.format(
                        "  turn total: %d in  |  %d out  |  %s",
                        totalIn, totalOut, Spinner.formatElapsed(totalMs))));
                return;
            }

            String prose = ToolCallParser.stripToolCalls(assistantText);
            if (!prose.isEmpty()) {
                System.out.println(Ansi.dim(prose));
            }

            StringBuilder resultsMsg = new StringBuilder();
            for (ToolCall call : calls) {
                System.out.println();
                System.out.println(Ansi.yellow("> " + call.name()) + Ansi.dim(" (id=" + call.id() + ")"));

                ToolResult r = executeOne(call);
                if ("web_search".equals(call.name())) webSearches++;
                else if ("web_fetch".equals(call.name())) webFetches++;
                resultsMsg.append(r.toXml(call.name(), call.id())).append("\n");

                String preview = r.content();
                if (preview.length() > 500) preview = preview.substring(0, 500) + "... " + Ansi.dim("[truncated display]");
                String statusLabel = "ok".equals(r.status()) ? Ansi.green("ok") : Ansi.red("error");
                System.out.println(Ansi.dim("  [") + statusLabel + Ansi.dim("] ") + preview);
            }

            Message toolResultMsg = Message.user(resultsMsg.toString());
            history.add(toolResultMsg);
            store.append(toolResultMsg);
        }

        Message cap = Message.user("You have used all " + CFG.agentMaxRounds() + " tool-call rounds for this turn. "
                + "Stop calling tools and respond to the user with a plain-text summary of what you have done and what remains.");
        history.add(cap);
        store.append(cap);
        try {
            Spinner spinner = new Spinner(Ansi.dim("summarizing"));
            spinner.start();
            OllamaClient.ChatResult result;
            try {
                result = ollama.chat(history);
            } finally {
                spinner.stop();
            }
            totalIn += result.inputTokens();
            totalOut += result.outputTokens();
            totalMs += result.durationMs();
            Message finalMsg = Message.assistant(result.content());
            history.add(finalMsg);
            store.append(finalMsg);
            System.out.println();
            System.out.println(ToolCallParser.stripToolCalls(result.content()));
            System.out.println();
            System.out.println(Ansi.gray(String.format(
                    "  turn total (capped): %d in  |  %d out  |  %s",
                    totalIn, totalOut, Spinner.formatElapsed(totalMs))));
        } catch (IOException e) {
            System.err.println();
            System.err.println(Ansi.red("[ollama error on cap summary] ") + e.getMessage());
        }
    }

    private ToolResult executeOne(ToolCall call) {
        Tool tool = registry.get(call.name()).orElse(null);
        if (tool == null) {
            return ToolResult.error("Unknown tool: " + call.name()
                    + ". Available: " + String.join(", ", registry.all().stream().map(Tool::name).toList()));
        }
        if (tool.requiresApproval()) {
            String preview = buildPreview(call);
            try {
                if (approvals.request(call.name(), preview) == ApprovalManager.Decision.DENIED) {
                    return ToolResult.error("User denied execution of " + call.name());
                }
            } catch (IOException e) {
                return ToolResult.error("Approval prompt failed: " + e.getMessage());
            }
        }
        try {
            return tool.execute(call, toolCtx);
        } catch (RuntimeException e) {
            return ToolResult.error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private String maybeStockResearchNudge(int searches, int fetches, int nudgesAlready) {
        if (mode != Mode.STOCK_RESEARCH) return null;
        if (nudgesAlready >= CFG.stockMaxNudges()) return null;
        int minSearches = CFG.stockMinSearches();
        int minFetches = CFG.stockMinFetches();
        if (searches >= minSearches && fetches >= minFetches) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("STOP. You are about to respond to the user, but you have NOT completed the required research depth.\n\n");
        sb.append("Progress so far: web_search=").append(searches).append(" (need >= ").append(minSearches).append("), ");
        sb.append("web_fetch=").append(fetches).append(" (need >= ").append(minFetches).append(").\n\n");
        sb.append("Do NOT produce the final brief yet. Emit more tool calls now:\n");
        if (searches < minSearches) {
            sb.append("- Run additional web_search calls with different queries (financials, screener.in, analyst targets, risks).\n");
        }
        if (fetches < minFetches) {
            sb.append("- Use web_fetch (NOT file_read) on the most promising URLs from your search results. ");
            sb.append("Extract concrete numbers (revenue, margins, P/E, debt, etc.) into research_<TICKER>.md via edit.\n");
        }
        sb.append("\nRespond now with <tool_call> blocks only.");
        return sb.toString();
    }

    private String buildPreview(ToolCall call) {
        StringBuilder sb = new StringBuilder();
        call.args().forEach((k, v) -> {
            String display = v.length() > 2000 ? v.substring(0, 2000) + "... [truncated]" : v;
            sb.append(k).append(": ").append(display).append("\n");
        });
        return sb.toString();
    }

    private void maybeCompact() {
        int total = 0;
        for (Message m : history) total += m.content().length();
        if (total <= CFG.agentCompactCharBudget()) return;

        int keepRecentMessages = 8;
        List<Message> compacted = new ArrayList<>(history.size());
        int cutoff = Math.max(1, history.size() - keepRecentMessages);

        for (int i = 0; i < history.size(); i++) {
            Message m = history.get(i);
            if (i == 0 || i >= cutoff) {
                compacted.add(m);
            } else if ("user".equals(m.role()) && m.content().contains("<tool_result")) {
                compacted.add(new Message(m.role(), "[tool results elided to save context]", m.timestamp()));
            } else {
                compacted.add(m);
            }
        }
        history.clear();
        history.addAll(compacted);
    }
}
