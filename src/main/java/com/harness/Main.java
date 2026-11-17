package com.harness;

import com.harness.cli.Cli;
import picocli.CommandLine;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        int exit = new CommandLine(new Cli()).execute(args);
        System.exit(exit);
    }
}
