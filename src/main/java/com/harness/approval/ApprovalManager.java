package com.harness.approval;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public final class ApprovalManager {
    private boolean yolo;
    private final BufferedReader stdin;
    private final Set<String> approvedForTurn = new HashSet<>();

    public ApprovalManager(boolean yolo, BufferedReader stdin) {
        this.yolo = yolo;
        this.stdin = stdin;
    }

    public void setYolo(boolean v) { this.yolo = v; }
    public boolean isYolo() { return yolo; }

    public void resetTurn() { approvedForTurn.clear(); }

    public enum Decision { APPROVED, DENIED }

    public Decision request(String toolName, String argsPreview) throws IOException {
        if (yolo) return Decision.APPROVED;
        if (approvedForTurn.contains(toolName)) return Decision.APPROVED;

        System.out.println();
        System.out.println("============================================================");
        System.out.println("APPROVAL REQUIRED: " + toolName);
        System.out.println("------------------------------------------------------------");
        System.out.println(argsPreview);
        System.out.println("============================================================");
        System.out.print("Approve? [y]es / [N]o / [a]ll this turn: ");
        System.out.flush();

        String line = stdin.readLine();
        if (line == null) return Decision.DENIED;
        String s = line.trim().toLowerCase();
        if (s.equals("a") || s.equals("all")) {
            approvedForTurn.add(toolName);
            return Decision.APPROVED;
        }
        if (s.equals("y") || s.equals("yes")) return Decision.APPROVED;
        return Decision.DENIED;
    }
}
