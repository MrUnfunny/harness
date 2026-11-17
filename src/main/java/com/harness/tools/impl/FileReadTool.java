package com.harness.tools.impl;

import com.harness.config.AppConfig;
import com.harness.tools.Tool;
import com.harness.tools.ToolCall;
import com.harness.tools.ToolContext;
import com.harness.tools.ToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class FileReadTool implements Tool {
    private static final AppConfig CFG = AppConfig.get();

    @Override public String name() { return "file_read"; }
    @Override public String description() {
        return "Read a LOCAL text file from the project root. DO NOT pass URLs here — for webpages use web_fetch. "
                + "Returns content with 1-based line numbers.";
    }
    @Override public List<ArgSpec> argSpecs() {
        return List.of(
                new ArgSpec("path", "File path relative to project root (or absolute inside it).", true),
                new ArgSpec("offset", "1-based line to start from (default 1).", false),
                new ArgSpec("limit", "Max lines to return (default " + CFG.fileReadDefaultMaxLines() + ").", false));
    }
    @Override public boolean requiresApproval() { return false; }

    @Override
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        try {
            Path p = ctx.paths().resolveSafe(call.arg("path"));
            if (!Files.isRegularFile(p)) return ToolResult.error("Not a file: " + p);

            int defaultMax = CFG.fileReadDefaultMaxLines();
            int offset = parseInt(call.arg("offset", "1"), 1);
            int limit = parseInt(call.arg("limit", String.valueOf(defaultMax)), defaultMax);
            if (offset < 1) offset = 1;

            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            int start = offset - 1;
            int end = Math.min(lines.size(), start + limit);
            for (int i = start; i < end; i++) {
                sb.append(String.format("%6d\t%s%n", i + 1, lines.get(i)));
            }
            if (end < lines.size()) {
                sb.append("... (").append(lines.size() - end).append(" more lines)\n");
            }
            return ToolResult.ok(sb.toString());
        } catch (IOException e) {
            return ToolResult.error(e.getMessage());
        }
    }

    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
    }
}
