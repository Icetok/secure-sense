package me.istok.securesense.service;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;

import me.istok.securesense.service.detector.CameraAccessDetector;
import me.istok.securesense.service.detector.Detector;
import me.istok.securesense.service.detector.DetectorHub;
import me.istok.securesense.service.detector.LocationAccessDetector;
import me.istok.securesense.service.detector.LogcatDetector;
import me.istok.securesense.service.detector.MicrophoneAccessDetector;

public class LogcatRemoteService extends ILogcatRemoteService.Stub {

    // Tag for logging
    private static final String TAG = "LogcatRemoteService";

    // Thread responsible for reading logcat
    private Thread logcatThread;

    // BufferedReader to read logcat output line by line
    private BufferedReader reader;

    // The actual logcat process
    private Process logcatProcess;

    // List of all active detectors (e.g., mic, camera, location)
    private List<Detector> detectors = null;

    // Optional context (may be null on older Android versions)
    private final Context ctx;

    // Reference to sink that receives logs and alerts (set from main service)
    @Nullable
    private ILogSink sink;

    // Helper to emit a log line to the sink (currently unused)
    private void post(String line) {
        if (sink != null) try { sink.onLine(line); } catch (RemoteException ignore) {}
    }

    // Constructor sets the context and initializes all detectors
    public LogcatRemoteService(@Nullable Context context) {
        this.ctx = context; // can be null for API < 13 (used only by some detectors)
        this.detectors = createDefaultDetectors();
        Log.i(TAG, "instantiated, ctx = " + ctx);
    }

    // Creates and registers all default detectors with the DetectorHub
    private List<Detector> createDefaultDetectors() {
        Log.i(TAG, "Creating default detectors...");
        List<Detector> list = Arrays.asList(
                new LocationAccessDetector(ctx),
                new MicrophoneAccessDetector(ctx),
                new CameraAccessDetector(ctx)
        );
        for (Detector d : list) DetectorHub.register(d); // Register each detector globally
        Log.i(TAG, "Detectors initialized: " + list.size());
        for (Detector detector : list) {
            Log.i(TAG, "Detector: " + detector.getClass().getSimpleName());
        }
        return list;
    }

    // Starts the logcat monitoring thread and passes logs to detectors
    @Override
    public void startLogcat(ILogSink s) throws RemoteException {
        this.sink = s;                // Save sink for alerts/logs
        Detector.setSink(s);          // Set sink in Detector base class
        Log.i(TAG, "startLogcat() CALLED in " + android.os.Process.myPid());

        // Ensure detectors are initialized (in case of deferred init)
        if (detectors == null) {
            Log.i(TAG, "Initializing detectors...");
            detectors = createDefaultDetectors();
        } else {
            Log.i(TAG, "Detectors already initialized.");
        }

        // Avoid starting the thread again if already running
        if (logcatThread != null && logcatThread.isAlive()) {
            Log.i(TAG, "Logcat thread already running.");
            return;
        }

        // Start a new thread to read logcat lines
        logcatThread = new Thread(() -> {
            Log.i(TAG, "Logcat thread started.");

            try {
                // Launch logcat command in time format
                logcatProcess = Runtime.getRuntime().exec("logcat -v time");
                reader = new BufferedReader(new InputStreamReader(logcatProcess.getInputStream()));

                String line;
                while ((line = reader.readLine()) != null && !Thread.currentThread().isInterrupted()) {
                    String raw = line.trim();

                    // Filter out own logs to prevent feedback loops
                    if (raw.contains(TAG) ||
                            raw.contains("me.istok.securesense") ||
                            raw.contains("MicrophoneAccess") ||
                            raw.contains("MicrophoneMatcher") ||
                            raw.contains("LocationAccess")) continue;

                    // Check each registered detector to see if it matches the log line
                    for (Detector detector : DetectorHub.all()) {
                        if (!detector.isEnabled()) continue;
                        if (detector.matches(raw)) {
                            detector.handle(raw); // Delegate handling of log line to the detector
                            //post(raw); // Optional debug broadcast (currently disabled)
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error reading logcat", e);
            }
        });
        logcatThread.start(); // Start logcat monitoring
    }

    // Enables or disables a specific detector via its ID
    @Override
    public void setEnabled(String id, boolean on) {
        DetectorHub.setEnabled(id, on);
    }

    // Shuts down the logcat thread and cleans up
    public void destroy() {
        if (logcatThread != null) logcatThread.interrupt();
        try {
            if (reader != null) reader.close();
            if (logcatProcess != null) logcatProcess.destroy();
        } catch (Exception e) {
            Log.e(TAG, "Error during shutdown", e);
        }
    }
}