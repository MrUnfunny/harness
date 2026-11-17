package com.harness.tools.impl;

import com.harness.tools.Tool;
import com.harness.tools.ToolCall;
import com.harness.tools.ToolContext;
import com.harness.tools.ToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public final class TodoWriteTool implements Tool {

    @Override public String name() { return "todo_write"; }
    @Override public String description() {
        return "Overwrite the session todo list. Persisted to .harness/todos.md. Use '[ ] ...' and '[x] ...' lines.";
    }
    @Override public List<ArgSpec> argSpecs() {
        return List.of(new ArgSpec("items", "The full todo list as markdown checkbox lines.", true));
    }
    @Override public boolean requiresApproval() { return false; }

    @Override
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        try {
            String items = call.arg("items");
            Files.writeString(ctx.paths().todosFile(), items, StandardCharsets.UTF_8);
            long lines = items.lines().filter(l -> !l.isBlank()).count();
            return ToolResult.ok("Saved " + lines + " todo line(s) to " + ctx.paths().todosFile());
        } catch (IOException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
