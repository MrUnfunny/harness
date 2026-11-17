package com.harness.agent;

public enum Mode {
    CODING, NEUTRAL, STOCK_RESEARCH;

    public static Mode parse(String s) {
        if (s == null) return CODING;
        String k = s.trim().toLowerCase().replace('-', '_');
        return switch (k) {
            case "coding", "code", "dev"                                -> CODING;
            case "neutral", "general", "default"                        -> NEUTRAL;
            case "stock_research", "stock", "research", "invest", "investing" -> STOCK_RESEARCH;
            default -> throw new IllegalArgumentException(
                    "Unknown mode: '" + s + "'. Valid: coding | neutral | stock_research");
        };
    }

    public String label() {
        return switch (this) {
            case CODING         -> "coding";
            case NEUTRAL        -> "neutral";
            case STOCK_RESEARCH -> "stock_research";
        };
    }
}
