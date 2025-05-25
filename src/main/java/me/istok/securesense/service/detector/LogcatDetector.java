package me.istok.securesense.service.detector;

import androidx.annotation.NonNull;

/**
 * Interface that defines the basic contract for a detector that analyzes logcat output.
 *
 * Implementing classes (like MicrophoneAccessDetector, LocationAccessDetector, etc.)
 * must define:
 * - how to identify relevant log lines (`matches`)
 * - how to handle them if they are relevant (`handle`)
 * - a stable identifier (`id`) used for settings and control
 *
 * This interface is used by the logcat reading thread in the SecureSense service.
 */
public interface LogcatDetector {

    /**
     * Returns a stable identifier for the detector.
     *
     * This ID is used in:
     * - shared preferences (to remember detector enable/disable state)
     * - UI toggles
     * - detector registration map (DetectorHub)
     *
     * Examples: "microphone", "location", "camera"
     */
    @NonNull String id();

    /**
     * Determines whether this detector is interested in the given logcat line.
     *
     * This method is called for every line that comes from logcat.
     * If it returns true, the `handle()` method will be called next.
     *
     * @param line the full logcat line
     * @return true if the line is interesting and should be handled
     */
    boolean matches(@NonNull String line);

    /**
     * Called only if `matches()` returned true and the detector is currently enabled.
     *
     * This is where the actual logic for parsing, logging, or triggering notifications
     * should be implemented by subclasses.
     *
     * @param line the full logcat line
     */
    void handle(@NonNull String line);
}