package com.harness.tools;

import java.util.List;

public interface Tool {
    String name();
    String description();
    List<ArgSpec> argSpecs();
    boolean requiresApproval();
    ToolResult execute(ToolCall call, ToolContext ctx);

    record ArgSpec(String name, String description, boolean required) {}
}
