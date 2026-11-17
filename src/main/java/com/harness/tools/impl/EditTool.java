package com.harness.tools.impl;

import com.harness.tools.Tool;
import com.harness.tools.ToolCall;
import com.harness.tools.ToolContext;
import com.harness.tools.ToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class EditTool implements Tool {

    @Override public String name() { return "edit"; }
    @Override public String description() {
        return "Replace an exact string in an existing file. By default old_string must occur exactly once.";
    }
    @Override public List<ArgSpec> argSpecs() {
        return List.of(
                new ArgSpec("path", "File path relative to project root.", true),
                new ArgSpec("old_string", "Exact existing text to replace.", true),
                new ArgSpec("new_string", "Replacement text.", true),
                new ArgSpec("replace_all", "If 'true', replace every occurrence (default false).", false));
    }
    @Override public boolean requiresApproval() { return true; }

    @Override
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        try {
            Path p = ctx.paths().resolveSafe(call.arg("path"));
            if (!Files.isRegularFile(p)) return ToolResult.error("Not a file: " + p);

            String oldStr = call.arg("old_string");
            String newStr = call.arg("new_string");
            boolean replaceAll = Boolean.parseBoolean(call.arg("replace_all", "false").trim());

            if (oldStr.isEmpty()) return ToolResult.error("old_string must not be empty");

            String content = Files.readString(p, StandardCharsets.UTF_8);
            int count = countOccurrences(content, oldStr);
            if (count == 0) return ToolResult.error("old_string not found in " + p);
            if (!replaceAll && count > 1) {
                return ToolResult.error("old_string occurs " + count + " times; pass replace_all=true or provide more context");
            }

            String updated = replaceAll
                    ? content.replace(oldStr, newStr)
                    : replaceFirstLiteral(content, oldStr, newStr);

            Files.writeString(p, updated, StandardCharsets.UTF_8);
            return ToolResult.ok("Replaced " + (replaceAll ? count : 1) + " occurrence(s) in " + p);
        } catch (IOException e) {
            return ToolResult.error(e.getMessage());
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int i = 0, n = 0;
        while ((i = haystack.indexOf(needle, i)) != -1) { n++; i += needle.length(); }
        return n;
    }

    private static String replaceFirstLiteral(String haystack, String needle, String replacement) {
        int i = haystack.indexOf(needle);
        return haystack.substring(0, i) + replacement + haystack.substring(i + needle.length());
    }
}
