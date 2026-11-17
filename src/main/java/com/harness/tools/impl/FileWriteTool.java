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

public final class FileWriteTool implements Tool {

    @Override public String name() { return "file_write"; }
    @Override public String description() {
        return "Create or overwrite a file. Parent directories are created if missing. "
                + "The `content` arg must contain the FULL file body — never call this with an empty or missing content arg.";
    }
    @Override public List<ArgSpec> argSpecs() {
        return List.of(
                new ArgSpec("path", "File path relative to project root.", true),
                new ArgSpec("content", "Full file content to write. Must be non-empty.", true));
    }
    @Override public boolean requiresApproval() { return true; }

    @Override
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        try {
            String pathArg = call.arg("path");
            if (pathArg == null || pathArg.isBlank()) return ToolResult.error("`path` arg is required");
            if (!call.args().containsKey("content")) {
                return ToolResult.error("`content` arg is missing. file_write requires the full file body as the `content` arg; "
                        + "do not emit a file_write call with only a path.");
            }
            String content = call.arg("content");
            if (content.isEmpty()) {
                return ToolResult.error("`content` arg is empty. To create an intentionally-empty file, pass a single newline; "
                        + "otherwise include the full file body.");
            }
            Path p = ctx.paths().resolveSafe(pathArg);
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Files.writeString(p, content, StandardCharsets.UTF_8);
            return ToolResult.ok("Wrote " + content.length() + " chars to " + p);
        } catch (IOException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
