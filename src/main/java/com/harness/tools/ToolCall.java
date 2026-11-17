package com.harness.tools;

import java.util.Map;

public record ToolCall(String name, String id, Map<String, String> args) {
    public String arg(String key) {
        return args.getOrDefault(key, "");
    }

    public String arg(String key, String fallback) {
        String v = args.get(key);
        return v == null ? fallback : v;
    }
}
