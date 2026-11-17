package com.harness.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ProjectPaths {
    private final Path root;
    private final Path stateDir;

    public ProjectPaths(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(this.root)) {
            throw new IOException("Project root does not exist or is not a directory: " + this.root);
        }
        this.stateDir = this.root.resolve(".harness");
        Files.createDirectories(stateDir);
        Files.createDirectories(stateDir.resolve("sessions"));
    }

    public Path root()               { return root; }
    public Path stateDir()           { return stateDir; }
    public Path sessionsDir()        { return stateDir.resolve("sessions"); }
    public Path currentSessionFile() { return stateDir.resolve("current-session"); }
    public Path todosFile()          { return stateDir.resolve("todos.md"); }

    /** Resolves a relative-or-absolute path against the project root and verifies it stays within. */
    public Path resolveSafe(String pathStr) throws IOException {
        Path p = Path.of(pathStr);
        Path resolved = (p.isAbsolute() ? p : root.resolve(p)).toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new IOException("Path escapes project root: " + pathStr);
        }
        return resolved;
    }
}
