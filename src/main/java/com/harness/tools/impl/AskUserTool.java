package com.harness.tools.impl;

import com.harness.tools.Tool;
import com.harness.tools.ToolCall;
import com.harness.tools.ToolContext;
import com.harness.tools.ToolResult;

import java.io.IOException;
import java.util.List;

public final class AskUserTool implements Tool {

    @Override public String name() { return "ask_user"; }
    @Override public String description() {
        return "Ask the user a clarifying question and wait for their typed reply. Use sparingly; only when genuinely blocked.";
    }
    @Override public List<ArgSpec> argSpecs() {
        return List.of(new ArgSpec("question", "The question to show the user.", true));
    }
    @Override public boolean requiresApproval() { return false; }

    @Override
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        String q = call.arg("question").trim();
        if (q.isEmpty()) return ToolResult.error("question is required");
        System.out.println();
        System.out.println("[model asks] " + q);
        System.out.print("> ");
        System.out.flush();
        try {
            String reply = ctx.stdin().readLine();
            return ToolResult.ok(reply == null ? "" : reply);
        } catch (IOException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
