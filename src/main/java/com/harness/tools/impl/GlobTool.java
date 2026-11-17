package com.harness.tools.impl;

import com.harness.config.AppConfig;
import com.harness.tools.Tool;
import com.harness.tools.ToolCall;
import com.harness.tools.ToolContext;
import com.harness.tools.ToolResult;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class GlobTool implements Tool {
    private static final int MAX_RESULTS = AppConfig.get().globMaxResults();

    @Override public String name() { return "glob"; }
    @Override public String description() {
        return "Find files matching a glob pattern (e.g. '**/*.java'). Searches from the project root (or 'path' arg if given).";
    }
    @Override public List<ArgSpec> argSpecs() {
        return List.of(
                new ArgSpec("pattern", "Glob pattern, e.g. '**/*.java' or 'src/**/Main.*'.", true),
                new ArgSpec("path", "Subdirectory to search in (default project root).", false));
    }
    @Override public boolean requiresApproval() { return false; }

    @Override
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        try {
            String rel = call.arg("path", ".");
            Path base = ctx.paths().resolveSafe(rel.isBlank() ? "." : rel);
            if (!Files.isDirectory(base)) return ToolResult.error("Not a directory: " + base);

            String pattern = call.arg("pattern").trim();
            if (pattern.isEmpty()) return ToolResult.error("pattern is required");

            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            List<Path> hits = new ArrayList<>();

            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (name.equals(".git") || name.equals("target") || name.equals("node_modules") || name.equals(".harness")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                    Path rel = base.relativize(file);
                    if (matcher.matches(rel)) hits.add(file);
                    return hits.size() >= MAX_RESULTS ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path f, IOException e) { return FileVisitResult.CONTINUE; }
            });

            hits.sort(Comparator.comparing(Path::toString));
            StringBuilder sb = new StringBuilder();
            for (Path h : hits) sb.append(ctx.paths().root().relativize(h)).append("\n");
            if (hits.isEmpty()) sb.append("(no matches)\n");
            else if (hits.size() >= MAX_RESULTS) sb.append("(truncated at ").append(MAX_RESULTS).append(")\n");
            return ToolResult.ok(sb.toString());
        } catch (IOException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
