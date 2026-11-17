package com.harness.tools;

import com.harness.persistence.ProjectPaths;

import java.io.BufferedReader;

public record ToolContext(ProjectPaths paths, BufferedReader stdin) {}
