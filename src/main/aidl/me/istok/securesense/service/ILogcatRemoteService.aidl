// ILogcatRemoteService.aidl
package me.istok.securesense.service;

// Import custom AIDL interface used for sending log lines and alerts
import me.istok.securesense.service.ILogSink;

/**
 * AIDL interface used by the SecureSense background user service (running via Shizuku).
 *
 * This interface is implemented by LogcatRemoteService and allows the main app service
 * (AccessMonitorService) to control it via IPC. It enables:
 *
 * 1. Starting the logcat detector pipeline with a live sink for callbacks.
 * 2. Enabling or disabling individual detectors at runtime.
 */
interface ILogcatRemoteService {

    /**
     * Starts logcat monitoring with the provided sink.
     * The sink will receive each matched log line (via onLine())
     * and alert messages (via onAlert()).
     *
     * @param sink Callback interface to send log and alert updates to the main process.
     */
    void startLogcat(in ILogSink sink);

    /**
     * Enables or disables a detector (e.g. mic, camera, location) by ID.
     * Called in response to toggle switches from the DetectorFragment UI.
     *
     * @param id    The unique identifier of the detector (e.g. "microphone")
     * @param on    True to enable the detector, false to disable
     */
    void setEnabled(String id, boolean on);
}