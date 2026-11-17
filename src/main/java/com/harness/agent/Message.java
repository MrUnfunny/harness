package com.harness.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Message(
        @JsonProperty("role") String role,
        @JsonProperty("content") String content,
        @JsonProperty("timestamp") Instant timestamp) {

    @JsonCreator
    public Message(@JsonProperty("role") String role,
                   @JsonProperty("content") String content,
                   @JsonProperty("timestamp") Instant timestamp) {
        this.role = role;
        this.content = content == null ? "" : content;
        this.timestamp = timestamp == null ? Instant.now() : timestamp;
    }

    public static Message system(String content)    { return new Message("system", content, Instant.now()); }
    public static Message user(String content)      { return new Message("user", content, Instant.now()); }
    public static Message assistant(String content) { return new Message("assistant", content, Instant.now()); }
}
