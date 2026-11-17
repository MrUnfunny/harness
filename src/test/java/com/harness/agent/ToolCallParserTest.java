package com.harness.agent;

import com.harness.tools.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallParserTest {

    @Test
    void parsesSingleCallWithArgs() {
        String content = """
                Let me read that file.
                <tool_call name="file_read" id="c1">
                  <arg name="path">src/Main.java</arg>
                  <arg name="limit">100</arg>
                </tool_call>
                """;
        List<ToolCall> calls = ToolCallParser.parse(content);
        assertEquals(1, calls.size());
        assertEquals("file_read", calls.get(0).name());
        assertEquals("c1", calls.get(0).id());
        assertEquals("src/Main.java", calls.get(0).arg("path"));
        assertEquals("100", calls.get(0).arg("limit"));
    }

    @Test
    void parsesMultipleCallsInOrder() {
        String content = """
                <tool_call name="list_dir" id="c1"><arg name="path">.</arg></tool_call>
                prose between
                <tool_call name="glob" id="c2"><arg name="pattern">**/*.java</arg></tool_call>
                """;
        List<ToolCall> calls = ToolCallParser.parse(content);
        assertEquals(2, calls.size());
        assertEquals("list_dir", calls.get(0).name());
        assertEquals("glob", calls.get(1).name());
    }

    @Test
    void handlesMultilineArgValue() {
        String content = """
                <tool_call name="file_write" id="c1">
                  <arg name="path">note.txt</arg>
                  <arg name="content">line one
                line two
                line three</arg>
                </tool_call>
                """;
        List<ToolCall> calls = ToolCallParser.parse(content);
        assertEquals(1, calls.size());
        String v = calls.get(0).arg("content");
        assertTrue(v.contains("line one"));
        assertTrue(v.contains("line three"));
    }

    @Test
    void returnsEmptyWhenNoCalls() {
        assertTrue(ToolCallParser.parse("just prose with no calls").isEmpty());
        assertTrue(ToolCallParser.parse("").isEmpty());
        assertTrue(ToolCallParser.parse(null).isEmpty());
    }

    @Test
    void assignsAutoIdWhenMissing() {
        String content = "<tool_call name=\"list_dir\"><arg name=\"path\">.</arg></tool_call>";
        List<ToolCall> calls = ToolCallParser.parse(content);
        assertEquals(1, calls.size());
        assertTrue(calls.get(0).id().startsWith("auto-"));
    }

    @Test
    void stripToolCallsRemovesBlocksLeavesProse() {
        String content = "Before.\n<tool_call name=\"x\" id=\"c1\"></tool_call>\nAfter.";
        String stripped = ToolCallParser.stripToolCalls(content);
        assertEquals("Before.\n\nAfter.", stripped);
    }

    @Test
    void parsesJsonNameArgumentsForm() {
        String content = """
                <tool_call>{"name":"file_read","arguments":{"path":"Main.java","limit":"50"}}</tool_call>
                """;
        List<ToolCall> calls = ToolCallParser.parse(content);
        assertEquals(1, calls.size());
        assertEquals("file_read", calls.get(0).name());
        assertEquals("Main.java", calls.get(0).arg("path"));
        assertEquals("50", calls.get(0).arg("limit"));
    }

    @Test
    void parsesJsonActionFormWithoutClosingTag() {
        String content = """
                <tool_call>
                {"action":"web_fetch","url":"https://a.example"}
                <tool_call>
                {"action":"web_fetch","url":"https://b.example"}
                """;
        List<ToolCall> calls = ToolCallParser.parse(content);
        assertEquals(2, calls.size());
        assertEquals("web_fetch", calls.get(0).name());
        assertEquals("https://a.example", calls.get(0).arg("url"));
        assertEquals("web_fetch", calls.get(1).name());
        assertEquals("https://b.example", calls.get(1).arg("url"));
    }

    @Test
    void parsesJsonToolInputForm() {
        String content = "<tool_call>{\"tool\":\"glob\",\"input\":{\"pattern\":\"**/*.md\"}}</tool_call>";
        List<ToolCall> calls = ToolCallParser.parse(content);
        assertEquals(1, calls.size());
        assertEquals("glob", calls.get(0).name());
        assertEquals("**/*.md", calls.get(0).arg("pattern"));
    }

    @Test
    void parsesHybridXmlAttrsWithJsonBody() {
        String content = """
                <tool_call name="web_search" id="1">{"query":"bharat forge financials"}</tool_call>
                <tool_call name="web_fetch" id="2">
                  {"url":"https://example.com/a"}
                </tool_call>
                """;
        List<ToolCall> calls = ToolCallParser.parse(content);
        assertEquals(2, calls.size());
        assertEquals("web_search", calls.get(0).name());
        assertEquals("1", calls.get(0).id());
        assertEquals("bharat forge financials", calls.get(0).arg("query"));
        assertEquals("web_fetch", calls.get(1).name());
        assertEquals("2", calls.get(1).id());
        assertEquals("https://example.com/a", calls.get(1).arg("url"));
    }

    @Test
    void parsesOpenAiFunctionWrapperWithStringifiedArgs() {
        String content = "<tool_call>{\"id\":\"call_7\",\"function\":{\"name\":\"web_search\",\"arguments\":\"{\\\"query\\\":\\\"apple earnings\\\"}\"}}</tool_call>";
        List<ToolCall> calls = ToolCallParser.parse(content);
        assertEquals(1, calls.size());
        assertEquals("web_search", calls.get(0).name());
        assertEquals("call_7", calls.get(0).id());
        assertEquals("apple earnings", calls.get(0).arg("query"));
    }

    @Test
    void stripToolCallsHandlesUnclosedJsonBlocks() {
        String content = """
                Here is my plan.
                <tool_call>
                {"action":"web_fetch","url":"https://a.example"}
                <tool_call>
                {"action":"web_fetch","url":"https://b.example"}
                Done.""";
        String stripped = ToolCallParser.stripToolCalls(content);
        assertTrue(stripped.contains("Here is my plan."));
        assertTrue(stripped.contains("Done."));
        assertTrue(!stripped.contains("web_fetch"));
        assertTrue(!stripped.contains("a.example"));
    }
}
