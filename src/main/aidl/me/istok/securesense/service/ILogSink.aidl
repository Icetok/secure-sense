// ILogSink.aidl
package me.istok.securesense.service;

/**
 * AIDL callback interface for receiving log lines and alert notifications
 * from the logcat-monitoring service (LogcatRemoteService).
 *
 * This interface is implemented by the main app service (AccessMonitorService),
 * and called remotely from within the Shizuku-bound user service.
 *
 * The `oneway` keyword ensures calls are asynchronous and non-blocking.
 */
oneway interface ILogSink {

    /**
     * Called for every matched log line by any active detector.
     *
     * The text parameter typically contains a formatted string showing
     * which package triggered the event and what kind of sensitive access occurred.
     *
     * Example:
     *   "📍 com.example.app accessed location (fused)"
     *
     * @param text A formatted log message to be shown in the MonitorFragment.
     */
    void onLine(String text);

    /**
     * Called when a detector wants to trigger a high-priority user notification.
     *
     * These alerts are typically sent when a sensitive sensor is accessed
     * and the detector deems it important enough to notify the user (rate-limited).
     *
     * @param title   The notification title (e.g. "SecureSense Alert")
     * @param message The body content (e.g. "Camera Accessed. Check Monitor for details.")
     */
    void onAlert(String title, String message);
}