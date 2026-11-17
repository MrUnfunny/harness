package com.harness.tools.impl;

import com.harness.config.AppConfig;
import com.harness.tools.Tool;
import com.harness.tools.ToolCall;
import com.harness.tools.ToolContext;
import com.harness.tools.ToolResult;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class ShellTool implements Tool {
    private static final AppConfig CFG = AppConfig.get();
    private static final int DEFAULT_TIMEOUT = CFG.shellDefaultTimeoutSec();
    private static final int MAX_TIMEOUT = CFG.shellMaxTimeoutSec();
    private static final int MAX_STREAM_BYTES = CFG.shellMaxStreamBytes();

    @Override public String name() { return "shell"; }
    @Override public String description() {
        return "Run a shell command. On Windows uses 'cmd.exe /c'. cwd = project root. Use python for math/logic.";
    }
    @Override public List<ArgSpec> argSpecs() {
        return List.of(
                new ArgSpec("command", "Command line to execute.", true),
                new ArgSpec("timeout_seconds",
                        "Timeout (default " + DEFAULT_TIMEOUT + ", max " + MAX_TIMEOUT + ").", false));
    }
    @Override public boolean requiresApproval() { return true; }

    @Override
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        String cmd = call.arg("command");
        if (cmd.isEmpty()) return ToolResult.error("command is required");

        int timeout = DEFAULT_TIMEOUT;
        try {
            timeout = Math.min(MAX_TIMEOUT,
                    Integer.parseInt(call.arg("timeout_seconds", String.valueOf(DEFAULT_TIMEOUT)).trim()));
        } catch (NumberFormatException ignored) {}

        boolean isWindows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        ProcessBuilder pb = isWindows
                ? new ProcessBuilder("cmd.exe", "/c", cmd)
                : new ProcessBuilder("sh", "-c", cmd);
        pb.directory(ctx.paths().root().toFile());
        pb.redirectErrorStream(false);

        try {
            Process proc = pb.start();
            StreamReader stdout = new StreamReader(proc.getInputStream());
            StreamReader stderr = new StreamReader(proc.getErrorStream());
            stdout.start();
            stderr.start();

            boolean finished = proc.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                stdout.join(500);
                stderr.join(500);
                return ToolResult.error("Timed out after " + timeout + "s\n"
                        + "--- stdout ---\n" + stdout.text()
                        + "\n--- stderr ---\n" + stderr.text());
            }
            stdout.join();
            stderr.join();

            StringBuilder out = new StringBuilder();
            out.append("exit=").append(proc.exitValue()).append("\n");
            out.append("--- stdout ---\n").append(stdout.text());
            if (!stderr.text().isEmpty()) out.append("\n--- stderr ---\n").append(stderr.text());
            return proc.exitValue() == 0 ? ToolResult.ok(out.toString()) : ToolResult.error(out.toString());
        } catch (IOException | InterruptedException e) {
            return ToolResult.error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static final class StreamReader extends Thread {
        private final InputStream in;
        private final StringBuilder buf = new StringBuilder();
        StreamReader(InputStream in) { this.in = in; setDaemon(true); }
        @Override public void run() {
            try {
                byte[] chunk = new byte[4096];
                int read;
                while ((read = in.read(chunk)) != -1) {
                    if (buf.length() < MAX_STREAM_BYTES) {
                        int remain = MAX_STREAM_BYTES - buf.length();
                        buf.append(new String(chunk, 0, Math.min(read, remain), StandardCharsets.UTF_8));
                        if (buf.length() >= MAX_STREAM_BYTES) buf.append("\n[truncated]");
                    }
                }
            } catch (IOException ignored) {}
        }
        String text() { return buf.toString(); }
    }
}
