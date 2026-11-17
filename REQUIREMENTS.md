# Harness — Requirements

A local-first, ReAct-style agent harness in Java that drives a Gemma 4 model served by Ollama. Modeled loosely after Claude Code: tool-using, project-scoped, human-in-the-loop.

## 1. Goals

- Interactive CLI where a user chats with Gemma 4 and the model can invoke tools to read/modify files, run shell commands, search the web, etc.
- Conversation and task state persist across runs (resume a session).
- Risky tools (shell, write, edit) require explicit user approval unless `--yolo` is set.
- All file and shell operations are scoped to a fixed **project root** passed at startup.
- Hard cap of 25 tool-call rounds per user turn to prevent runaway loops.

## 2. Non-goals (v1)

- Multi-agent orchestration / sub-agent spawning.
- Streaming UI (responses can be emitted after the model finishes a turn).
- Windows-native Ollama — we assume Ollama runs in WSL2 and is reachable on `localhost:11434` from Windows.
- Background processes (no long-running `run_background` / `read_output`).
- Git-aware tooling (the model can call `git` via `shell` if needed).

## 3. Environment

- **Host OS:** Windows 11; harness runs as a normal Windows Java process.
- **Model runtime:** Ollama in WSL2, reachable at `http://localhost:11434`. If unreachable, the harness will suggest setting `OLLAMA_HOST=0.0.0.0` inside WSL.
- **Model:** `gemma4` (exact Ollama tag configurable).
- **Build:** Maven, JDK 21 LTS.
- **Dependencies (target minimal set):**
  - `java.net.http.HttpClient` (stdlib) for Ollama HTTP.
  - Jackson (`jackson-databind`) for JSON request/response bodies.
  - `picocli` for CLI parsing.
  - JUnit 5 for tests.

## 4. Architecture

```
+-------------------+       +------------------+       +-----------------+
|   CLI (picocli)   | <---> |   Agent Loop     | <---> |  Ollama Client  |
+-------------------+       +------------------+       +-----------------+
                                    |
                          +---------+----------+
                          |                    |
                    +-----v-----+       +------v------+
                    | Tool      |       | Persistence |
                    | Registry  |       | (.harness/) |
                    +-----------+       +-------------+
                          |
                +---------+-----------+
                |         |           |
           +----v--+  +---v----+  +---v----+
           | file  |  | shell  |  | web    |
           | tools |  | tools  |  | tools  |
           +-------+  +--------+  +--------+
```

### 4.1 Components

- **CLI** — parses args, sets up project root, opens REPL.
- **Agent Loop** — per user turn, loops: send history → receive assistant text → parse tool calls → execute → append results → repeat. Exits loop when assistant emits no tool calls or cap is hit.
- **Ollama Client** — thin wrapper over `/api/chat` (non-streaming for v1).
- **Tool Registry** — map of tool name → `Tool` implementation. Each tool declares its name, schema, and whether it requires approval.
- **Persistence** — reads/writes session state to `<project-root>/.harness/`.

### 4.2 Runtime flow (single user turn)

1. User types input.
2. Append user message to history.
3. For up to 25 rounds:
   - POST history to Ollama `/api/chat`, receive assistant message.
   - Append assistant message to history.
   - Parse XML tool calls from assistant content.
   - If none: break; print assistant's plain text to user.
   - For each call:
     - If the tool requires approval and `--yolo` is off, prompt user `[y/N/a(ll for session)]`.
     - Execute; capture result or error.
     - Append a `tool` role message with the result (wrapped in `<tool_result name="..." id="...">...</tool_result>`).
4. If cap hit, append a synthetic message telling the model it ran out of rounds and must summarize.

## 5. Ollama integration

- Endpoint: `POST {host}/api/chat`
- Request body:
  ```json
  {
    "model": "gemma4",
    "messages": [...],
    "stream": false,
    "options": { "temperature": 0.2 }
  }
  ```
- Message roles: `system`, `user`, `assistant`, `tool` (tool results go in as role `tool` or as `user` content tagged with `<tool_result>` — TBD during implementation based on what Gemma 4 tolerates).
- No use of Ollama's native `tools` field — we are doing **ReAct via prompt engineering**, not native function calling.

## 6. ReAct protocol

### 6.1 System prompt (sketch)

The system prompt instructs the model to:
- Think step by step inside `<thought>...</thought>` (optional, stripped before display).
- Invoke tools using exactly the XML format below.
- Emit plain text only when done (no tool calls = end of turn).
- Never invent tool names or arguments not listed in the catalog.

### 6.2 Tool-call format

```xml
<tool_call name="TOOL_NAME" id="CALL_ID">
  <arg name="ARG1">value</arg>
  <arg name="ARG2">value possibly multi-line
spanning lines</arg>
</tool_call>
```

- `id` is a short model-generated string (e.g. `c1`, `c2`) used to pair results.
- Multiple calls in one assistant turn are allowed and executed in order.
- Arg values are treated as plain strings; no nested XML parsing.

### 6.3 Tool-result format (injected back into history)

```xml
<tool_result name="TOOL_NAME" id="CALL_ID" status="ok|error">
...result content or error message...
</tool_result>
```

### 6.4 Parsing & recovery

- Parse with a permissive regex-based extractor (not strict XML) since Gemma may emit near-but-not-quite XML.
- If a tool call is malformed (missing name, unknown tool, bad args): skip execution, inject a `tool_result` with `status="error"` describing the problem, let the model self-correct on the next round.
- Tool calls found outside `<tool_call>` tags (e.g. inline in prose) are ignored.

## 7. Tool catalog

All tools operate relative to the **project root**. Paths escaping the root (via `..` or absolute paths outside) are rejected.

| Tool | Requires approval | Description |
|------|-------------------|-------------|
| `file_read` | No | Read a file. Args: `path`, optional `offset`, `limit` (lines). |
| `file_write` | **Yes** | Overwrite or create a file. Args: `path`, `content`. |
| `edit` | **Yes** | Targeted string replace in an existing file. Args: `path`, `old_string`, `new_string`, optional `replace_all`. Fails if `old_string` is not unique and `replace_all` is false. |
| `list_dir` | No | List directory contents. Args: `path`. |
| `glob` | No | Find files matching a glob pattern. Args: `pattern`, optional `path`. |
| `grep` | No | Search file contents. Args: `pattern` (regex), optional `path`, optional `glob`, optional `max_results`. |
| `shell` | **Yes** | Execute a shell command. Args: `command`, optional `timeout_seconds` (default 30, max 300). Runs via `cmd.exe /c` on Windows, cwd = project root. |
| `web_fetch` | No | GET a URL and return text content (HTML stripped to plain text, capped at ~50k chars). Args: `url`. |
| `todo_write` | No | Overwrite the session todo list. Args: `items` (newline-separated `[ ] ...` / `[x] ...` lines). Persisted to `.harness/todos.md`. |
| `ask_user` | No (but blocks) | Ask the user a clarifying question mid-loop. Args: `question`. Returns the user's typed reply as the tool result. |

### 7.1 Approval semantics

- Approval prompt shows: tool name, full args (truncated at 2KB for display), and asks `[y/N/a]`.
- `a` = approve all further invocations of *this tool* for the remainder of the current user turn.
- `--yolo` flag skips all approval prompts.
- `ask_user` is never auto-approved away — it is the mechanism by which the model *requests* input, not a risky action.

## 8. Persistence

State directory: `<project-root>/.harness/`

```
.harness/
├── config.json          # project-local overrides (model name, temperature, etc.)
├── sessions/
│   └── 2026-04-18-1430.jsonl   # one JSON object per message, append-only
├── current-session      # pointer file (just the filename of the active session)
└── todos.md             # current todo list (managed by todo_write)
```

- On startup, if `current-session` exists, offer to resume (`[r]esume / [n]ew`).
- Session files are append-only JSONL: each line is `{role, content, timestamp, tool_calls?, tool_call_id?}`.
- Todos are a plain markdown file so the user can hand-edit between sessions.

## 9. Context window management

v1 strategy — simple and revisable:

- Track approximate token count (chars/4 heuristic).
- When combined history exceeds 75% of model's context window:
  - Drop oldest `tool` role messages first (keep their originating assistant messages for narrative continuity, replace result body with `[elided for context]`).
  - If still over, drop oldest `user`/`assistant` pairs, keeping the original system prompt and the most recent 4 turns.
- No summarization pass in v1 (would need another model call per compaction — defer).

## 10. CLI

```
harness --project-root <path> [--model gemma4] [--ollama-host http://localhost:11434]
        [--yolo] [--resume | --new]
```

REPL commands (prefix `/`):

- `/exit` — quit
- `/clear` — start a new session (prompts to confirm if unsaved work)
- `/todos` — print current todo list
- `/approve` — cycle approval mode: gated → yolo → gated
- `/history` — dump conversation history

## 11. Safety & error handling

- Path traversal guard on every file/dir tool (canonicalize, verify prefix matches project root).
- Shell commands run with a timeout; stdout/stderr captured, truncated at 64KB per stream, returned as tool result.
- Network errors to Ollama get 3 retries with exponential backoff, then surface to user.
- Tool exceptions are caught and returned as `status="error"` tool results — they never crash the loop.

## 12. Open questions / deferred

- Does Gemma 4's tokenizer and context window match what `ollama show` reports? (Verify during impl.)
- If ReAct quality is poor, fall back to Ollama native `tools` field.
- Summarizing old context instead of dropping — revisit if 75% ceiling hits often.
- Multi-turn approval memory across sessions (currently reset each session).

## 13. Milestones

1. **M1 — Skeleton:** CLI + Ollama client + echo loop (no tools). Verifies networking.
2. **M2 — Read-only tools:** `file_read`, `list_dir`, `glob`, `grep`, `web_fetch`, `ask_user`. ReAct parse/execute loop. No approval gating needed yet.
3. **M3 — Write tools + approval:** `file_write`, `edit`, `shell`, `todo_write`. Approval prompts. Path traversal guard.
4. **M4 — Persistence:** session save/resume, todos.md.
5. **M5 — Context mgmt + polish:** truncation strategy, `/` commands, error-recovery tuning against real Gemma 4 outputs.
