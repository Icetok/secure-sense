package me.istok.securesense.service.detector;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Central registry for all active Detector instances inside the user-service process.
 *
 * Responsibilities:
 * - Keeps track of which detectors are active.
 * - Provides access to all registered detectors for the logcat reader loop.
 * - Handles enabling/disabling detectors from the UI.
 * - Stores and manages a shared configurable log window interval (used for rate-limiting).
 */
public final class DetectorHub {

    // Shared rate-limit window (in milliseconds) for all detectors
    private static long windowMs = 10_000; // default: 10 seconds

    // Called to update the global log window from settings
    public static void updateWindow(long ms) {
        windowMs = ms;
    }

    // Getter for the shared log window
    public static long window() {
        return windowMs;
    }

    // Map of all registered detectors, using their `id()` as the key
    // Thread-safe version of LinkedHashMap
    private static final Map<String, Detector> DETECTORS =
            Collections.synchronizedMap(new LinkedHashMap<>());

    // Prevent instantiation
    private DetectorHub() {}

    /**
     * Register a detector instance.
     * Called once per detector when LogcatRemoteService is initialized.
     */
    public static void register(Detector d) {
        DETECTORS.put(d.id(), d);
    }

    /**
     * Enable or disable a specific detector.
     * Called when the user toggles a detector in the UI (SettingsFragment).
     */
    public static void setEnabled(String id, boolean on) {
        Detector d = DETECTORS.get(id);
        if (d != null) d.setEnabled(on);
    }

    /**
     * Retrieve all registered detectors.
     * Used in the logcat reading loop to iterate through and dispatch matches.
     */
    public static Iterable<Detector> all() {
        return DETECTORS.values();
    }

    /**
     * Inner static class to manage rate-limited user notifications.
     * Prevents notification spam for individual detector types.
     */
    public static final class Notify {

        // Broadcast action name used to signal the UI that a notification should be shown
        public static final String ACTION_SHOW_NOTIFICATION = "SS_SHOW_ALERT";

        // Keys used for passing notification title/text through the broadcast
        public static final String EXTRA_TITLE = "title";
        public static final String EXTRA_TEXT  = "text";

        // ID and channel used by AccessMonitorService for alert notifications
        public static final String ALERT_CHANNEL_ID      = "SecureSenseAlerts";
        public static final int    ALERT_NOTIFICATION_ID = 42;

        // Minimum time (in ms) between two notifications of the same detector ID
        private static final long WINDOW_MS = 20_000; // 20 seconds

        // Stores the last time each detector triggered a notification
        private static final Map<String, Long> last = new HashMap<>();

        /**
         * Returns true if a notification for the given detector ID is currently allowed.
         * This rate-limits user notifications across time for the same detector.
         */
        static synchronized boolean allowed(String id) {
            long now = System.currentTimeMillis();
            Long prev = last.get(id);
            if (prev == null || now - prev >= WINDOW_MS) {
                last.put(id, now);
                return true;
            }
            return false;
        }
    }
}