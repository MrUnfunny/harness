package com.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.tools.ToolCall;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses tool calls from an assistant's response. Accepts several formats emitted by different model tunes:
 *
 * 1. XML form (matches the system prompt):
 *      <tool_call name="NAME" id="ID">
 *        <arg name="K">V</arg>
 *      </tool_call>
 *
 * 2. Hybrid (XML tag attrs + JSON body, seen on Qwen):
 *      <tool_call name="NAME" id="ID">{"K":"V"}</tool_call>
 *
 * 3. JSON-inside-tool_call:
 *      <tool_call>{"name":"NAME","arguments":{"K":"V"}}</tool_call>
 *      <tool_call>{"action":"NAME","K":"V"}</tool_call>
 *      <tool_call>{"tool":"NAME","input":{"K":"V"}}</tool_call>
 *      <tool_call>{"function":{"name":"NAME","arguments":"{\"K\":\"V\"}"}}</tool_call>   // OpenAI-style
 *
 * Closing </tool_call> tags are tolerated but not required. Stringified `arguments` are re-parsed.
 */
public final class ToolCallParser {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OPEN = "<tool_call";
    private static final String CLOSE = "</tool_call>";

    private static final Pattern ARG = Pattern.compile(
            "<arg\\s+name=\"([^\"]+)\"\\s*>(.*?)</arg>", Pattern.DOTALL);
    private static final Pattern NAME_ATTR = Pattern.compile("name=\"([^\"]*)\"");
    private static final Pattern ID_ATTR = Pattern.compile("id=\"([^\"]*)\"");

    private ToolCallParser() {}

    /**
     * Describes a <tool_call> region in a larger string.
     *
     * @param openTagEnd index of the '>' that closes the opening tag
     * @param bodyEnd    exclusive end of the body (either the '<' of </tool_call> or end of inline JSON)
     * @param blockEnd   cursor position after the whole block (past </tool_call> if present)
     */
    private record Block(int openTagEnd, int bodyEnd, int blockEnd) {}

    public static List<ToolCall> parse(String content) {
        List<ToolCall> calls = new ArrayList<>();
        if (content == null || content.isEmpty()) return calls;

        int[] autoId = {0};
        int cursor = 0;
        while (cursor < content.length()) {
            int start = content.indexOf(OPEN, cursor);
            if (start < 0) break;

            Block block = readBlock(content, start);
            if (block == null) break;

            ToolCall call = extractCall(content, start, block, autoId);
            if (call != null) calls.add(call);
            cursor = block.blockEnd;
        }
        return calls;
    }

    public static String stripToolCalls(String content) {
        if (content == null) return "";
        StringBuilder sb = new StringBuilder(content.length());
        int cursor = 0;
        while (cursor < content.length()) {
            int start = content.indexOf(OPEN, cursor);
            if (start < 0) { sb.append(content, cursor, content.length()); break; }
            sb.append(content, cursor, start);

            Block block = readBlock(content, start);
            if (block == null) { cursor = content.length(); break; }
            cursor = block.blockEnd;
        }
        return sb.toString().trim();
    }

    /**
     * Locate the opening tag at {@code start} and determine where its body and the whole block end.
     * The body extends to whichever comes first: the matching {@code </tool_call>}, or the end of an
     * inline JSON object directly after the opening tag. Unclosed blocks are tolerated.
     */
    private static Block readBlock(String content, int start) {
        int tagEnd = content.indexOf('>', start);
        if (tagEnd < 0) return null;

        int close = content.indexOf(CLOSE, tagEnd);
        int nextOpen = content.indexOf(OPEN, tagEnd + 1);
        boolean hasOwnClose = close >= 0 && (nextOpen < 0 || close < nextOpen);
        if (hasOwnClose) {
            return new Block(tagEnd, close, close + CLOSE.length());
        }

        int braceStart = nextNonWhitespace(content, tagEnd + 1);
        if (braceStart >= 0 && content.charAt(braceStart) == '{') {
            int braceEnd = findMatchingBrace(content, braceStart);
            if (braceEnd >= 0) {
                int bodyEnd = braceEnd + 1;
                return new Block(tagEnd, bodyEnd, bodyEnd);
            }
        }
        return new Block(tagEnd, tagEnd + 1, tagEnd + 1);
    }

    private static ToolCall extractCall(String content, int start, Block block, int[] autoId) {
        String openTag = content.substring(start, block.openTagEnd + 1);
        String nameAttr = extractAttr(openTag, NAME_ATTR);
        String idAttr = extractAttr(openTag, ID_ATTR);
        String body = content.substring(block.openTagEnd + 1, block.bodyEnd);

        if (nameAttr != null) {
            Map<String, String> args = parseXmlArgs(body);
            if (args.isEmpty()) tryMergeJsonBody(body, args);
            return new ToolCall(nameAttr, resolveId(idAttr, autoId), args);
        }

        int braceStart = nextNonWhitespace(body, 0);
        if (braceStart < 0 || body.charAt(braceStart) != '{') return null;
        int braceEnd = findMatchingBrace(body, braceStart);
        if (braceEnd < 0) return null;
        return parseJsonCall(body.substring(braceStart, braceEnd + 1), idAttr, autoId);
    }

    private static Map<String, String> parseXmlArgs(String body) {
        Map<String, String> args = new LinkedHashMap<>();
        Matcher m = ARG.matcher(body);
        while (m.find()) args.put(m.group(1).trim(), m.group(2));
        return args;
    }

    private static void tryMergeJsonBody(String body, Map<String, String> args) {
        int bs = nextNonWhitespace(body, 0);
        if (bs < 0 || body.charAt(bs) != '{') return;
        int be = findMatchingBrace(body, bs);
        if (be >= 0) mergeJsonArgs(body.substring(bs, be + 1), args);
    }

    private static ToolCall parseJsonCall(String jsonStr, String idAttr, int[] autoId) {
        try {
            JsonNode outer = JSON.readTree(jsonStr);
            JsonNode fn = outer.get("function");
            JsonNode node = (fn != null && fn.isObject()) ? fn : outer;

            String name = null;
            Map<String, String> args = new LinkedHashMap<>();

            if (node.hasNonNull("name")) {
                name = node.get("name").asText();
                addArgsFromAny(firstNonNull(node, "arguments", "args", "parameters", "input"), args);
            } else if (node.hasNonNull("tool")) {
                name = node.get("tool").asText();
                addArgsFromAny(firstNonNull(node, "input", "arguments", "args", "parameters"), args);
            } else if (node.hasNonNull("action")) {
                name = node.get("action").asText();
                copyFlatFieldsAsArgs(node, args, "action", "id");
            }
            if (name == null || name.isBlank()) return null;

            String id = idAttr;
            if (isBlank(id) && outer.hasNonNull("id")) id = outer.get("id").asText();
            if (isBlank(id) && node != outer && node.hasNonNull("id")) id = node.get("id").asText();
            return new ToolCall(name, resolveId(id, autoId), args);
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonNode firstNonNull(JsonNode parent, String... keys) {
        for (String k : keys) {
            JsonNode v = parent.get(k);
            if (v != null && !v.isNull()) return v;
        }
        return null;
    }

    private static void addArgsFromAny(JsonNode a, Map<String, String> args) {
        if (a == null || a.isNull()) return;
        if (a.isObject()) { copyObjectToArgs(a, args); return; }
        if (a.isTextual()) {
            String s = a.asText().trim();
            if (s.startsWith("{") && s.endsWith("}")) mergeJsonArgs(s, args);
        }
    }

    private static void mergeJsonArgs(String jsonObj, Map<String, String> args) {
        try {
            JsonNode n = JSON.readTree(jsonObj);
            if (n.isObject()) copyObjectToArgs(n, args);
        } catch (Exception ignored) {}
    }

    private static void copyObjectToArgs(JsonNode obj, Map<String, String> args) {
        Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            args.put(e.getKey(), stringify(e.getValue()));
        }
    }

    private static void copyFlatFieldsAsArgs(JsonNode obj, Map<String, String> args, String... skipKeys) {
        Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String k = e.getKey();
            boolean skip = false;
            for (String s : skipKeys) if (s.equals(k)) { skip = true; break; }
            if (!skip) args.put(k, stringify(e.getValue()));
        }
    }

    private static String stringify(JsonNode n) {
        if (n == null || n.isNull()) return "";
        if (n.isTextual()) return n.asText();
        return n.toString();
    }

    private static String resolveId(String id, int[] autoId) {
        return isBlank(id) ? "auto-" + (++autoId[0]) : id;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String extractAttr(String openTag, Pattern attrPattern) {
        Matcher m = attrPattern.matcher(openTag);
        return m.find() ? m.group(1) : null;
    }

    private static int nextNonWhitespace(String s, int from) {
        for (int j = from; j < s.length(); j++) {
            if (!Character.isWhitespace(s.charAt(j))) return j;
        }
        return -1;
    }

    /** Scan s starting at openIdx (which must be '{') and return the index of the matching '}', or -1. */
    private static int findMatchingBrace(String s, int openIdx) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int j = openIdx; j < s.length(); j++) {
            char c = s.charAt(j);
            if (escape) { escape = false; continue; }
            if (inString) {
                if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return j;
            }
        }
        return -1;
    }
}
