package me.istok.securesense.service.detector;

import me.istok.securesense.service.ILogSink;

/**
 * Abstract base class for all detectors (e.g., location, microphone, camera).
 * Detectors are responsible for matching logcat lines and handling access events.
 *
 * Each detector implements `matches()` and `handle()` to define how it reacts
 * to a given log line. It can optionally send log messages and user notifications.
 */
public abstract class Detector {
    // Tracks whether this detector is enabled (controlled by UI or startup logic)
    private boolean enabled = true;

    // Returns a stable string identifier for the detector (e.g., "location", "microphone")
    public abstract String id();

    // Sink interface for sending logs and alerts back to the main service
    // It's set externally when the service starts
    private static volatile ILogSink sink;

    // Called by the service once, to pass the shared sink for all detectors
    public static void setSink(ILogSink s) {
        sink = s;
    }

    // Check whether this detector is currently enabled
    public boolean isEnabled() {
        return enabled;
    }

    // Enable or disable this detector (typically controlled from UI)
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // Each detector must implement a method to decide if a log line is relevant
    public abstract boolean matches(String line);

    // Each detector must implement how to handle a matching log line
    public abstract void handle(String line);

    // Sends a log message to the UI or persistent buffer via the sink
    protected void sendLog(String msg) {
        ILogSink s = sink;
        if (s != null) {
            try {
                s.onLine(msg);
            } catch (Exception ignored) {
                // Failing to send log line should not crash the app
            }
        }
    }

    // Sends a user-facing alert if this detector is allowed to send one
    // Uses global rate limiting via DetectorHub.Notify
    protected void notifyUser(String title, String msg) {
        // Skip if we're currently rate-limited for this detector's id
        if (!DetectorHub.Notify.allowed(id())) return;

        ILogSink s = sink;
        if (s != null) {
            try {
                s.onAlert(title, msg);
            } catch (Throwable ignored) {
                // Failing to send alert should not crash the app
            }
        }
    }

    // Returns the currently active time window (in ms) used for local rate limiting
    // This value is shared and can be updated dynamically via broadcasts
    long WINDOW_MS() {
        return DetectorHub.window();
    }
}