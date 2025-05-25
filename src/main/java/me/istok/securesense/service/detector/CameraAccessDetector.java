package me.istok.securesense.service.detector;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects attempts to access the camera based on logcat output.
 * Supports legacy Camera API, camera2 API, system/HAL access,
 * media encoders, MediaRecorder, and flashlight (torch) use.
 *
 * Applies per-app, per-kind rate limiting to avoid excessive logs/alerts.
 */
public class CameraAccessDetector extends Detector implements LogcatDetector {

    // Tag used for logging messages from this detector
    private static final String TAG = "CamAccess";

    /**
     * Categories of camera access events. Each is matched by a set of regex patterns.
     */
    private enum Kind {
        CAMERA_API,         // legacy android.hardware.Camera
        CAMERA2,            // camera2 API access
        CAMERA_SERVICE,     // HAL/system level service access
        CODEC,              // hardware encoder start (h264, hevc, etc.)
        MEDIARECORDER,      // MediaRecorder usage
        TORCH,              // flashlight usage
        UNKNOWN             // fallback if nothing matches
    }

    /**
     * Regex patterns used to detect each kind of access.
     * These patterns match relevant logcat entries for different camera usages.
     */
    private static final Map<Kind, Pattern> KIND_PATTERNS = new LinkedHashMap<>();
    static {
        KIND_PATTERNS.put(Kind.CAMERA_API,
                Pattern.compile("CameraClient@[0-9a-f]+.*(startPreview|takePicture|connect)", Pattern.CASE_INSENSITIVE));

        KIND_PATTERNS.put(Kind.CAMERA2,
                Pattern.compile("(CameraDevice|CaptureSession).*?configure|openCamera", Pattern.CASE_INSENSITIVE));

        KIND_PATTERNS.put(Kind.CAMERA_SERVICE,
                Pattern.compile("(CameraService|ICamera).*open|device.*open|HAL3.*open", Pattern.CASE_INSENSITIVE));

        KIND_PATTERNS.put(Kind.CODEC,
                Pattern.compile("(MediaCodec|CCodec).*encoder.*(avc|h264|hevc|vp8|vp9)", Pattern.CASE_INSENSITIVE));

        KIND_PATTERNS.put(Kind.MEDIARECORDER,
                Pattern.compile("MediaRecorder.*(start|prepare).*video", Pattern.CASE_INSENSITIVE));

        KIND_PATTERNS.put(Kind.TORCH,
                Pattern.compile("TorchState.*ON|FlashlightController.*turnOn", Pattern.CASE_INSENSITIVE));
    }

    // Regex helpers for extracting the UID/package name from log lines
    private static final Pattern UID_PKG  = Pattern.compile("uid=(\\d+).*?package=([\\w.]+)");
    private static final Pattern UID_ONLY = Pattern.compile("uid=(\\d+)");
    private static final Pattern ANY_PKG  = Pattern.compile("[A-Za-z]\\w*(?:\\.[A-Za-z]\\w*)+");

    // Map of last logged times for each (package, kind) pair
    private final Map<String, Map<Kind, Long>> lastLog = new LinkedHashMap<>();

    // Optional PackageManager for UID resolution
    private final @Nullable PackageManager pm;

    // Optional context for access to system services
    private Context context;

    /**
     * Constructs the detector with an optional context.
     * Context is used for resolving UID to package names.
     */
    public CameraAccessDetector(@Nullable Context ctx) {
        pm = (ctx != null) ? ctx.getPackageManager() : null;
        this.context = ctx;
    }

    /**
     * Unique ID of the detector used in broadcasts and settings.
     */
    @NonNull
    @Override
    public String id() {
        return "camera";
    }

    /**
     * Cheap pre-filter to avoid applying regex to irrelevant lines.
     * Returns true if the line is likely to be about camera access.
     */
    @Override
    public boolean matches(String l) {
        return l.contains("Camera") || l.contains("camera")
                || l.contains("ICamera") || l.contains("Torch")
                || l.contains("MediaRecorder")
                || l.contains("CaptureSession") || l.contains("CaptureRequest")
                || l.contains("MediaCodec") || l.contains("CCodec");
    }

    /**
     * Handles a matching log line.
     * Classifies the line, determines the package, and logs the access
     * if the event passes the per-package, per-kind rate limit.
     */
    @Override
    public void handle(String line) {
        Kind kind = classify(line);
        if (kind == Kind.UNKNOWN) return;

        String pkg = guessPackage(line);
        if (pkg == null) pkg = "unknown";

        long now = System.currentTimeMillis();
        if (shouldLog(pkg, kind, now)) {
            Log.i(TAG, "📷  " + pkg + " accessed camera (" + kind.name().toLowerCase() + ')');
            sendLog("📷  " + pkg + " accessed camera (" + kind.name().toLowerCase() + ')');
            notifyUser("SecureSense Alert", "Camera Accessed. Check Monitor for details.");
            remember(pkg, kind, now);
        }
    }

    /**
     * Tries to classify the log line as a specific kind of camera access.
     */
    private Kind classify(String line) {
        for (var e : KIND_PATTERNS.entrySet()) {
            if (e.getValue().matcher(line).find()) return e.getKey();
        }
        return Kind.UNKNOWN;
    }

    /**
     * Determines whether this (package, kind) combination should trigger a log/notification
     * based on the configured window duration.
     */
    private boolean shouldLog(String pkg, Kind k, long now) {
        Long last = lastLog.getOrDefault(pkg, Map.of()).get(k);
        return last == null || now - last >= WINDOW_MS();
    }

    /**
     * Records the time of a detected access and prunes old entries to prevent memory bloat.
     */
    private void remember(String pkg, Kind k, long now) {
        lastLog.computeIfAbsent(pkg, p -> new LinkedHashMap<>()).put(k, now);

        long cutoff = now - WINDOW_MS() * 2;
        for (Iterator<Map.Entry<String, Map<Kind, Long>>> it = lastLog.entrySet().iterator();
             it.hasNext();) {
            Map<Kind, Long> m = it.next().getValue();
            m.values().removeIf(t -> t < cutoff);
            if (m.isEmpty()) it.remove();
        }
    }

    /**
     * Attempts to extract the package name from the log line.
     * Falls back through several strategies:
     *  - columns after tag
     *  - uid=... package=...
     *  - UID resolution through PackageManager
     *  - generic token match
     */
    @Nullable
    private String guessPackage(String line) {
        String[] cols = line.split("\\s+");
        if (cols.length > 5) {
            for (int i = 3; i < cols.length; i++) {
                if (cols[i].length() == 1) break;
                String tok = cols[i].replace("...", "").replace("…", "");
                if (ANY_PKG.matcher(tok).matches()) return tok;
            }
        }

        Matcher m = UID_PKG.matcher(line);
        if (m.find()) return m.group(2);

        m = UID_ONLY.matcher(line);
        if (m.find() && pm != null) {
            try {
                String[] pkgs = pm.getPackagesForUid(Integer.parseInt(m.group(1)));
                if (pkgs != null && pkgs.length > 0) return pkgs[0];
            } catch (Throwable ignored) {}
        }

        m = ANY_PKG.matcher(line);
        return m.find() ? m.group() : null;
    }
}