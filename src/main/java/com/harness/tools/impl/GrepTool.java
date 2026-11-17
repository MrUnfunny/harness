package com.harness.tools.impl;

import com.harness.config.AppConfig;
import com.harness.tools.Tool;
import com.harness.tools.ToolCall;
import com.harness.tools.ToolContext;
import com.harness.tools.ToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class GrepTool implements Tool {
    private static final AppConfig CFG = AppConfig.get();
    private static final int DEFAULT_MAX = CFG.grepDefaultMaxResults();
    private static final long MAX_FILE_BYTES = CFG.grepMaxFileBytes();

    @Override public String name() { return "grep"; }
    @Override public String description() {
        return "Search file contents with a Java regex. Returns lines as 'path:lineno:content'.";
    }
    @Override public List<ArgSpec> argSpecs() {
        return List.of(
                new ArgSpec("pattern", "Regex pattern (Java syntax).", true),
                new ArgSpec("path", "Subdirectory to search in (default project root).", false),
                new ArgSpec("glob", "Optional glob filter on file paths (e.g. '**/*.java').", false),
                new ArgSpec("max_results", "Max matching lines to return (default " + DEFAULT_MAX + ").", false));
    }
    @Override public boolean requiresApproval() { return false; }

    @Override
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        try {
            String patStr = call.arg("pattern");
            if (patStr.isEmpty()) return ToolResult.error("pattern is required");

            Pattern pattern;
            try { pattern = Pattern.compile(patStr); }
            catch (PatternSyntaxException e) { return ToolResult.error("Invalid regex: " + e.getMessage()); }

            String rel = call.arg("path", ".");
            Path base = ctx.paths().resolveSafe(rel.isBlank() ? "." : rel);
            if (!Files.isDirectory(base)) return ToolResult.error("Not a directory: " + base);

            String globArg = call.arg("glob", "");
            PathMatcher globMatcher = globArg.isBlank()
                    ? null
                    : FileSystems.getDefault().getPathMatcher("glob:" + globArg);

            final int maxResults = parseIntOrDefault(call.arg("max_results", ""), DEFAULT_MAX);

            StringBuilder out = new StringBuilder();
            int[] matchCount = {0};
            Path searchRoot = base;

            Files.walkFileTree(searchRoot, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (name.equals(".git") || name.equals("target") || name.equals("node_modules") || name.equals(".harness")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                    if (matchCount[0] >= maxResults) return FileVisitResult.TERMINATE;
                    if (a.size() > MAX_FILE_BYTES) return FileVisitResult.CONTINUE;

                    Path relToBase = searchRoot.relativize(file);
                    if (globMatcher != null && !globMatcher.matches(relToBase)) return FileVisitResult.CONTINUE;

                    try {
                        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                        Path display = ctx.paths().root().relativize(file);
                        for (int i = 0; i < lines.size() && matchCount[0] < maxResults; i++) {
                            if (pattern.matcher(lines.get(i)).find()) {
                                out.append(display).append(":").append(i + 1).append(":").append(lines.get(i)).append("\n");
                                matchCount[0]++;
                            }
                        }
                    } catch (IOException ignored) {
                        // Binary / unreadable files — skip silently
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path f, IOException e) { return FileVisitResult.CONTINUE; }
            });

            if (matchCount[0] == 0) return ToolResult.ok("(no matches)\n");
            if (matchCount[0] >= maxResults) out.append("(truncated at ").append(maxResults).append(")\n");
            return ToolResult.ok(out.toString());
        } catch (IOException e) {
            return ToolResult.error(e.getMessage());
        }
    }

    private static int parseIntOrDefault(String s, int fallback) {
        if (s == null || s.isBlank()) return fallback;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }
}
