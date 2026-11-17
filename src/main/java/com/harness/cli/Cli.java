package com.harness.cli;

import com.harness.agent.AgentLoop;
import com.harness.agent.Message;
import com.harness.agent.Mode;
import com.harness.approval.ApprovalManager;
import com.harness.config.AppConfig;
import com.harness.ollama.OllamaClient;
import com.harness.persistence.ProjectPaths;
import com.harness.persistence.SessionStore;
import com.harness.prompt.SystemPromptBuilder;
import com.harness.tools.ToolContext;
import com.harness.tools.ToolRegistry;
import com.harness.tools.impl.AskUserTool;
import com.harness.tools.impl.EditTool;
import com.harness.tools.impl.FileReadTool;
import com.harness.tools.impl.FileWriteTool;
import com.harness.tools.impl.GlobTool;
import com.harness.tools.impl.GrepTool;
import com.harness.tools.impl.ListDirTool;
import com.harness.tools.impl.ShellTool;
import com.harness.tools.impl.TodoWriteTool;
import com.harness.tools.impl.WebFetchTool;
import com.harness.tools.impl.WebSearchTool;
import com.harness.ui.Ansi;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "harness",
        mixinStandardHelpOptions = true,
        version = "harness 0.1.0",
        defaultValueProvider = Cli.AppConfigDefaults.class,
        description = "ReAct-style agent harness for local Gemma via Ollama.")
public final class Cli implements Callable<Integer> {

    @Option(names = "--project-root", defaultValue = ".",
            description = "Path to the project root (default: current directory).")
    Path projectRoot;

    @Option(names = "--model", description = "Ollama model tag.", showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
    String model;

    @Option(names = "--ollama-host", description = "Ollama base URL.",
            showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
    String ollamaHost;

    @Option(names = "--temperature", description = "Sampling temperature.",
            showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
    double temperature;

    @Option(names = "--yolo", description = "Skip approval prompts for risky tools.")
    boolean yolo;

    @Option(names = "--mode", description = "Operating mode: coding | neutral | stock_research.",
            showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
    String modeArg;

    @Option(names = "--resume", description = "Resume the most recent session if one exists.")
    boolean resume;

    @Option(names = "--new", description = "Force a new session even if a prior one exists.")
    boolean forceNew;

    @Override
    public Integer call() throws Exception {
        Mode mode;
        try { mode = Mode.parse(modeArg); }
        catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 2;
        }

        ProjectPaths paths = new ProjectPaths(projectRoot);
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

        ToolRegistry registry = new ToolRegistry()
                .register(new FileReadTool())
                .register(new ListDirTool())
                .register(new GlobTool())
                .register(new GrepTool())
                .register(new FileWriteTool())
                .register(new EditTool())
                .register(new ShellTool())
                .register(new WebSearchTool())
                .register(new WebFetchTool())
                .register(new TodoWriteTool())
                .register(new AskUserTool());

        ApprovalManager approvals = new ApprovalManager(yolo, stdin);
        OllamaClient ollama = new OllamaClient(ollamaHost, model, temperature);
        ToolContext toolCtx = new ToolContext(paths, stdin);
        SessionStore store = new SessionStore(paths);

        List<Message> history = new ArrayList<>();
        boolean resumed = false;

        if (!forceNew && store.hasCurrentSession() && (resume || promptYesNo(stdin, "Resume prior session? [Y/n] ", true))) {
            try {
                history.addAll(store.resumeCurrentSession());
                resumed = true;
                System.out.println("[resumed " + history.size() + " messages from " + store.sessionFile().getFileName() + "]");
            } catch (IOException e) {
                System.err.println("Could not resume session (" + e.getMessage() + "); starting new.");
            }
        }

        if (!resumed) {
            store.startNewSession();
            Message sys = Message.system(SystemPromptBuilder.build(paths.root().toString(), registry, mode));
            history.add(sys);
            store.append(sys);
        }

        AgentLoop loop = new AgentLoop(ollama, registry, approvals, store, toolCtx, history, mode);

        printBanner(paths, ollama, approvals, mode, resumed);

        while (true) {
            System.out.println();
            System.out.print(Ansi.bold(Ansi.cyan("you> ")));
            System.out.flush();
            String line = stdin.readLine();
            if (line == null) break;
            String input = line.trim();
            if (input.isEmpty()) continue;

            if (input.startsWith("/")) {
                if (handleSlash(input, approvals, paths, history, stdin)) break;
                continue;
            }

            try {
                loop.runTurn(input);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("[interrupted]");
                break;
            }
        }
        return 0;
    }

    private void printBanner(ProjectPaths paths, OllamaClient ollama, ApprovalManager approvals, Mode mode, boolean resumed) {
        System.out.println(Ansi.bold(Ansi.cyan("harness 0.1.0")));
        System.out.println(Ansi.dim("  project root : ") + paths.root());
        System.out.println(Ansi.dim("  ollama host  : ") + ollama.host());
        System.out.println(Ansi.dim("  model        : ") + Ansi.bold(ollama.model()));
        System.out.println(Ansi.dim("  mode         : ") + Ansi.bold(mode.label())
                + (resumed ? Ansi.dim("  (from resumed session)") : ""));
        System.out.println(Ansi.dim("  approval     : ")
                + (approvals.isYolo() ? Ansi.yellow("yolo (no prompts)") : Ansi.green("gated")));
        System.out.println(Ansi.dim("  slash cmds   : /exit /clear /todos /approve /history"));
    }

    /** Returns true if the loop should exit. */
    private boolean handleSlash(String cmd,
                                ApprovalManager approvals,
                                ProjectPaths paths,
                                List<Message> history,
                                BufferedReader stdin) throws IOException {
        switch (cmd) {
            case "/exit", "/quit" -> {
                return true;
            }
            case "/clear" -> {
                if (promptYesNo(stdin, "Discard current session and start a new one? [y/N] ", false)) {
                    Files.deleteIfExists(paths.currentSessionFile());
                    System.out.println("[cleared — restart the CLI to begin a fresh session]");
                    return true;
                }
            }
            case "/todos" -> {
                if (Files.isRegularFile(paths.todosFile())) {
                    System.out.println(Files.readString(paths.todosFile(), StandardCharsets.UTF_8));
                } else {
                    System.out.println("(no todos)");
                }
            }
            case "/approve" -> {
                approvals.setYolo(!approvals.isYolo());
                System.out.println("[approval mode: " + (approvals.isYolo() ? "yolo" : "gated") + "]");
            }
            case "/history" -> {
                for (Message m : history) {
                    System.out.println("--- " + m.role() + " @ " + m.timestamp() + " ---");
                    System.out.println(m.content());
                }
            }
            default -> System.out.println("Unknown command: " + cmd);
        }
        return false;
    }

    /**
     * Supplies CLI flag defaults from {@link AppConfig} so the source of truth lives in
     * {@code application.properties}. Picocli queries this provider whenever a flag's
     * {@code defaultValue} attribute is absent.
     */
    public static final class AppConfigDefaults implements CommandLine.IDefaultValueProvider {
        @Override
        public String defaultValue(CommandLine.Model.ArgSpec argSpec) {
            if (!(argSpec instanceof CommandLine.Model.OptionSpec opt)) return null;
            AppConfig c = AppConfig.get();
            for (String n : opt.names()) {
                switch (n) {
                    case "--model":        return c.ollamaModel();
                    case "--ollama-host":  return c.ollamaHost();
                    case "--temperature":  return String.valueOf(c.ollamaTemperature());
                    case "--mode":         return c.agentDefaultMode();
                    default: // try next alias
                }
            }
            return null;
        }
    }

    private static boolean promptYesNo(BufferedReader stdin, String prompt, boolean defaultYes) throws IOException {
        System.out.print(prompt);
        System.out.flush();
        String line = stdin.readLine();
        if (line == null) return false;
        String s = line.trim().toLowerCase();
        if (s.isEmpty()) return defaultYes;
        return s.equals("y") || s.equals("yes");
    }
}
