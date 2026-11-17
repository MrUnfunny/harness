package com.harness.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.harness.agent.Message;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class SessionStore {
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final DateTimeFormatter SESSION_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");

    private final ProjectPaths paths;
    private Path sessionFile;

    public SessionStore(ProjectPaths paths) {
        this.paths = paths;
    }

    public Path sessionFile() { return sessionFile; }

    public void startNewSession() throws IOException {
        String name = LocalDateTime.now().format(SESSION_TS) + ".jsonl";
        this.sessionFile = paths.sessionsDir().resolve(name);
        Files.createFile(sessionFile);
        Files.writeString(paths.currentSessionFile(), name, StandardCharsets.UTF_8);
    }

    public boolean hasCurrentSession() {
        return Files.isRegularFile(paths.currentSessionFile());
    }

    public List<Message> resumeCurrentSession() throws IOException {
        String name = Files.readString(paths.currentSessionFile(), StandardCharsets.UTF_8).trim();
        this.sessionFile = paths.sessionsDir().resolve(name);
        if (!Files.isRegularFile(sessionFile)) {
            throw new IOException("Current session file missing: " + sessionFile);
        }
        List<Message> msgs = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(sessionFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                msgs.add(JSON.readValue(line, Message.class));
            }
        }
        return msgs;
    }

    public void append(Message m) throws IOException {
        if (sessionFile == null) throw new IllegalStateException("No active session");
        try (BufferedWriter w = Files.newBufferedWriter(sessionFile, StandardCharsets.UTF_8,
                StandardOpenOption.APPEND)) {
            w.write(JSON.writeValueAsString(m));
            w.newLine();
        }
    }
}
