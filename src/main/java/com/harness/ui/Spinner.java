package com.harness.ui;

/** A single-line spinner that overwrites itself with a live elapsed timer. */
public final class Spinner {
    private static final char[] FRAMES = {'|', '/', '-', '\\'};
    private static final int LINE_WIDTH = 80;

    private final String label;
    private final Thread thread;
    private final long startNanos;
    private volatile boolean running = true;
    private volatile long stoppedNanos;

    public Spinner(String label) {
        this.label = label;
        this.startNanos = System.nanoTime();
        this.thread = new Thread(this::run, "harness-spinner");
        this.thread.setDaemon(true);
    }

    public void start() { thread.start(); }

    /** Stops the spinner, clears the line, and returns the elapsed milliseconds. */
    public long stop() {
        running = false;
        stoppedNanos = System.nanoTime();
        thread.interrupt();
        try { thread.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        System.out.print("\r" + " ".repeat(LINE_WIDTH) + "\r");
        System.out.flush();
        return (stoppedNanos - startNanos) / 1_000_000L;
    }

    private void run() {
        int i = 0;
        while (running) {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            String frame = String.valueOf(FRAMES[i++ % FRAMES.length]);
            System.out.print("\r" + Ansi.cyan(frame) + " " + label + " " + Ansi.dim(formatElapsed(elapsedMs)) + "   ");
            System.out.flush();
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
    }

    public static String formatElapsed(long ms) {
        if (ms < 1000) return ms + "ms";
        double s = ms / 1000.0;
        if (s < 60) return String.format("%.1fs", s);
        long mins = (long) (s / 60);
        double rem = s - mins * 60;
        return String.format("%dm %.0fs", mins, rem);
    }
}
