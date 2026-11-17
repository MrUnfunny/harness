# harness

A small coding agent in Java 21, in the spirit of Claude Code, that runs entirely on a local model (Gemma via Ollama). You chat with it in a terminal; it reads, searches and edits files in one project folder, runs shell commands, and fetches web pages, asking before it does anything risky.

I built it to understand what actually sits inside an agent harness: the loop, the tool protocol, approvals, and state. The full design is in [REQUIREMENTS.md](REQUIREMENTS.md).

## How it works

- **ReAct loop over plain prompting.** No native function calling: the system prompt teaches the model an XML `<tool_call>` format, a permissive parser pulls calls out of the reply, and results go back as `<tool_result>` blocks. A malformed call becomes an error result the model can correct on its next round.
- **Hard cap of 25 tool rounds per turn.** When it hits the cap, the model is told to stop and summarise instead of looping forever.
- **11 tools behind one interface.** `file_read`, `file_write`, `edit`, `list_dir`, `glob`, `grep`, `shell`, `web_fetch`, `web_search`, `todo_write` and `ask_user`, registered in a `ToolRegistry`. Each tool declares its own schema and whether it needs approval.
- **Approvals.** `shell`, `file_write` and `edit` prompt `y / N / all-for-session` unless `--yolo` is set.
- **Sandboxed to a project root.** Any path that escapes the root (`..` or absolute) is rejected.
- **Resumable sessions.** The conversation and todos persist as JSONL under `<project>/.harness/`.
- **Few dependencies.** It uses the JDK's `HttpClient` for Ollama, Jackson, picocli and JUnit 5.

## Prerequisites

- JDK 21+
- Maven 3.9+
- Ollama running (locally or in WSL2) with a Gemma model pulled

## Build

```bash
mvn package
```

Produces a fat jar at `target/harness.jar`.

Run tests only:

```bash
mvn test
```

Clean build artifacts:

```bash
mvn clean
```

## Run

```bash
java -jar target\harness.jar --project-root C:\path\to\your\project --model gemma4:e4b
```

### Common flags

| Flag | Default | Purpose |
|------|---------|---------|
| `--project-root` | *(required)* | Directory the agent is scoped to. All file/shell tools run here. |
| `--model` | `gemma4` | Ollama model tag. Use the exact tag shown by `ollama list` (e.g. `gemma4:e4b`). |
| `--ollama-host` | `http://localhost:11434` | Ollama base URL. |
| `--temperature` | `0.2` | Sampling temperature. |
| `--yolo` | off | Skip approval prompts for shell / file_write / edit. |
| `--resume` | off | Resume the prior session without asking. |
| `--new` | off | Force a fresh session. |

### REPL slash commands

- `/exit` — quit
- `/clear` — discard the current session
- `/todos` — print the todo list saved by `todo_write`
- `/approve` — toggle between gated and yolo
- `/history` — dump the full conversation

## Troubleshooting

**`Ollama request failed after 3 attempts`**
1. Check Ollama is up: `curl http://localhost:11434/api/tags`
2. Confirm your model tag matches exactly what `ollama list` shows — `--model gemma4` won't match an installed `gemma4:e4b`.
3. If Ollama is in WSL2 and unreachable from Windows, set `OLLAMA_HOST=0.0.0.0` inside WSL before starting the server.

**Build fails with `local variables referenced from an inner class must be final`**
You're on an older snapshot — pull latest.

**Gemma emits malformed tool calls**
The parser is permissive but not magic. Bump `--temperature` down to `0.1`, or swap to a stronger-at-tool-use model (e.g. `qwen3:8b`) with `--model qwen3:8b`.

## Project layout

```
src/main/java/com/harness/
├── Main.java                  entry point
├── cli/Cli.java               picocli + REPL
├── agent/                     ReAct loop, message model, XML parser
├── ollama/OllamaClient.java   /api/chat over HTTP
├── approval/                  y/N/a gating
├── prompt/                    system prompt builder
├── persistence/               session JSONL + .harness/ layout
└── tools/
    ├── Tool, ToolCall, ToolResult, ToolContext, ToolRegistry
    └── impl/                  file_read, file_write, edit, list_dir,
                               glob, grep, shell, web_fetch,
                               todo_write, ask_user
```
