package com.harness.tools;

public record ToolResult(String status, String content) {
    public static ToolResult ok(String content)    { return new ToolResult("ok", content); }
    public static ToolResult error(String content) { return new ToolResult("error", content); }

    public String toXml(String toolName, String id) {
        return "<tool_result name=\"" + toolName + "\" id=\"" + id + "\" status=\"" + status + "\">\n"
                + content + "\n</tool_result>";
    }
}
