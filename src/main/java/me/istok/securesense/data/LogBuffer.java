package me.istok.securesense.data;

import java.util.List;

/**
 * A simple in-memory ring buffer to store recent log messages.
 *
 * Stores up to MAX_LINES recent entries. When the buffer is full,
 * the oldest entries are removed to make room for new ones.
 *
 * Used by the monitor fragment to display log entries from the detectors.
 */
public final class LogBuffer {

    // Maximum number of log lines to retain
    private static final int MAX_LINES = 500;

    // The list holding the actual log lines (acts as a ring buffer)
    private static final List<String> LINES = new java.util.LinkedList<>();

    // Private constructor to prevent instantiation
    private LogBuffer() {}

    /**
     * Adds a new line to the log buffer. If the size exceeds the limit,
     * the oldest line is removed.
     *
     * @param line the log line to add
     */
    public static synchronized void add(String line) {
        LINES.add(line);
        if (LINES.size() > MAX_LINES) {
            LINES.remove(0);  // Remove oldest entry to maintain size limit
        }
    }

    /**
     * Returns a copy of the current log buffer so callers can safely iterate.
     * This avoids ConcurrentModificationException since the internal list is mutable.
     *
     * @return a snapshot (shallow copy) of the log lines
     */
    public static synchronized java.util.List<String> snapshot() {
        return new java.util.ArrayList<>(LINES);
    }
}