package com.harness.ui;

public final class Ansi {
    private Ansi() {}

    private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getenv("HARNESS_COLOR"));

    public static final String RESET  = "\u001B[0m";
    public static final String BOLD   = "\u001B[1m";
    public static final String DIM    = "\u001B[2m";
    public static final String RED    = "\u001B[31m";
    public static final String GREEN  = "\u001B[32m";
    public static final String YELLOW = "\u001B[33m";
    public static final String BLUE   = "\u001B[34m";
    public static final String CYAN   = "\u001B[36m";
    public static final String GRAY   = "\u001B[90m";

    private static String wrap(String code, String s) {
        return ENABLED ? (code + s + RESET) : s;
    }

    public static String bold(String s)   { return wrap(BOLD, s); }
    public static String dim(String s)    { return wrap(DIM, s); }
    public static String red(String s)    { return wrap(RED, s); }
    public static String green(String s)  { return wrap(GREEN, s); }
    public static String yellow(String s) { return wrap(YELLOW, s); }
    public static String blue(String s)   { return wrap(BLUE, s); }
    public static String cyan(String s)   { return wrap(CYAN, s); }
    public static String gray(String s)   { return wrap(GRAY, s); }
}
