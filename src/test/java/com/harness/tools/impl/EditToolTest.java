package com.harness.tools.impl;

import com.harness.persistence.ProjectPaths;
import com.harness.tools.ToolCall;
import com.harness.tools.ToolContext;
import com.harness.tools.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditToolTest {

    private ToolContext ctx(Path root) throws IOException {
        ProjectPaths paths = new ProjectPaths(root);
        return new ToolContext(paths, new BufferedReader(new StringReader("")));
    }

    private ToolCall call(Map<String, String> args) {
        return new ToolCall("edit", "t1", args);
    }

    @Test
    void replacesUniqueOccurrence(@TempDir Path root) throws IOException {
        Path f = root.resolve("a.txt");
        Files.writeString(f, "hello world\n", StandardCharsets.UTF_8);

        Map<String, String> args = new LinkedHashMap<>();
        args.put("path", "a.txt");
        args.put("old_string", "world");
        args.put("new_string", "there");

        ToolResult r = new EditTool().execute(call(args), ctx(root));
        assertEquals("ok", r.status());
        assertEquals("hello there\n", Files.readString(f, StandardCharsets.UTF_8));
    }

    @Test
    void refusesAmbiguousReplacementUnlessReplaceAll(@TempDir Path root) throws IOException {
        Path f = root.resolve("a.txt");
        Files.writeString(f, "a a a\n", StandardCharsets.UTF_8);

        Map<String, String> args = new LinkedHashMap<>();
        args.put("path", "a.txt");
        args.put("old_string", "a");
        args.put("new_string", "b");

        ToolResult r = new EditTool().execute(call(args), ctx(root));
        assertEquals("error", r.status());
        assertTrue(r.content().contains("occurs 3 times"));

        args.put("replace_all", "true");
        ToolResult r2 = new EditTool().execute(call(args), ctx(root));
        assertEquals("ok", r2.status());
        assertEquals("b b b\n", Files.readString(f, StandardCharsets.UTF_8));
    }

    @Test
    void errorsWhenOldStringMissing(@TempDir Path root) throws IOException {
        Path f = root.resolve("a.txt");
        Files.writeString(f, "hello\n", StandardCharsets.UTF_8);

        Map<String, String> args = new LinkedHashMap<>();
        args.put("path", "a.txt");
        args.put("old_string", "nope");
        args.put("new_string", "x");

        ToolResult r = new EditTool().execute(call(args), ctx(root));
        assertEquals("error", r.status());
        assertTrue(r.content().contains("not found"));
    }

    @Test
    void rejectsPathOutsideRoot(@TempDir Path root) throws IOException {
        Map<String, String> args = new LinkedHashMap<>();
        args.put("path", "../evil.txt");
        args.put("old_string", "x");
        args.put("new_string", "y");

        ToolResult r = new EditTool().execute(call(args), ctx(root));
        assertEquals("error", r.status());
        assertTrue(r.content().contains("escapes project root"));
    }
}
