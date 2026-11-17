package com.harness.tools.impl;

import com.harness.tools.Tool;
import com.harness.tools.ToolCall;
import com.harness.tools.ToolContext;
import com.harness.tools.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public final class ListDirTool implements Tool {

    @Override public String name() { return "list_dir"; }
    @Override public String description() {
        return "List entries in a directory (non-recursive). Directories are suffixed with '/'.";
    }
    @Override public List<ArgSpec> argSpecs() {
        return List.of(new ArgSpec("path", "Directory path relative to project root (default '.').", false));
    }
    @Override public boolean requiresApproval() { return false; }

    @Override
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        try {
            String rel = call.arg("path", ".");
            Path p = ctx.paths().resolveSafe(rel.isBlank() ? "." : rel);
            if (!Files.isDirectory(p)) return ToolResult.error("Not a directory: " + p);

            StringBuilder sb = new StringBuilder();
            try (Stream<Path> s = Files.list(p)) {
                s.sorted().forEach(entry -> {
                    sb.append(entry.getFileName());
                    if (Files.isDirectory(entry)) sb.append("/");
                    sb.append("\n");
                });
            }
            if (sb.length() == 0) sb.append("(empty)\n");
            return ToolResult.ok(sb.toString());
        } catch (IOException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
