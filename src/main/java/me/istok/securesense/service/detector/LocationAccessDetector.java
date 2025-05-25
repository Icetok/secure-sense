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
 * A detector that monitors logcat output for lines indicating location access events
 * (e.g., GPS, fused location, last known location).
 *
 * It identifies location access by matching certain patterns in the logcat line,
 * then determines which app is responsible and rate-limits similar detections
 * to avoid notification or logging spam.
 */
public class LocationAccessDetector extends Detector implements LogcatDetector {

    private static final String TAG = "LocationAccess";

    /**
     * Enumeration of different types of location access events we are interested in.
     */
    private enum Kind {
        REQUEST, LAST_KNOWN, FUSED, GNSS, AIAI, REPORT, CHANGED, UNKNOWN
    }

    /**
     * Maps each kind of location access to its associated regex pattern.
     * These are used to classify logcat lines based on their content.
     */
    private static final Map<Kind, Pattern> KIND_PATTERNS = new LinkedHashMap<>();
    static {
        KIND_PATTERNS.put(Kind.REQUEST,
                Pattern.compile("request(LocationUpdates|SingleUpdate)", Pattern.CASE_INSENSITIVE));
        KIND_PATTERNS.put(Kind.LAST_KNOWN,
                Pattern.compile("getLastKnownLocation", Pattern.CASE_INSENSITIVE));
        KIND_PATTERNS.put(Kind.FUSED,
                Pattern.compile("FusedLocation.*location (delivery|update|report|blocked)",
                        Pattern.CASE_INSENSITIVE));
        KIND_PATTERNS.put(Kind.GNSS,
                Pattern.compile("Gnss:onGnssLocationCb", Pattern.CASE_INSENSITIVE));
        KIND_PATTERNS.put(Kind.AIAI,
                Pattern.compile("AiAiLocation.*Request(ing)? location updates?",
                        Pattern.CASE_INSENSITIVE));
        KIND_PATTERNS.put(Kind.REPORT,
                Pattern.compile("(LocationReporter sending|Successfully inserted .* locations)",
                        Pattern.CASE_INSENSITIVE));
        KIND_PATTERNS.put(Kind.CHANGED,
                Pattern.compile("Location changed", Pattern.CASE_INSENSITIVE));
    }

    // Regex patterns to extract package names and UIDs from log lines
    private static final Pattern UID_PKG = Pattern.compile("uid=(\\d+).*?package=([\\w.]+)");
    private static final Pattern UID_ONLY = Pattern.compile("uid=(\\d+)");
    private static final Pattern ANY_PKG = Pattern.compile("[A-Za-z]\\w*(?:\\.[A-Za-z]\\w*)+");

    // Holds the last seen time for each (package, kind) pair to enable per-type rate limiting
    private final Map<String, Map<Kind, Long>> lastLog = new LinkedHashMap<>();

    // Android package manager used for UID-to-package resolution
    private final @Nullable PackageManager pm;

    // Optional context, used to obtain the package manager if available
    private Context context;

    /**
     * Constructor that accepts an optional Context (can be null).
     * If a context is provided, it retrieves a PackageManager for UID resolution.
     */
    public LocationAccessDetector(@Nullable Context ctx) {
        this.pm = ctx != null ? ctx.getPackageManager() : null;
        this.context = ctx;
    }

    @NonNull
    @Override
    public String id() {
        return "location";
    }

    /**
     * Determines whether this detector is interested in the given logcat line.
     * A line is relevant if it contains known location-related keywords.
     */
    @Override
    public boolean matches(String line) {
        return line.contains("Location")
                || line.contains("FusedLocation")
                || line.contains("Gnss:onGnssLocationCb");
    }

    /**
     * Handles a logcat line if it was matched by `matches()`.
     * Identifies the kind of location event, extracts the app/package,
     * applies per-type rate limiting, and emits logs and notifications.
     */
    @Override
    public void handle(String line) {
        Kind kind = classify(line);
        if (kind == Kind.UNKNOWN) return;

        String pkg = guessPackage(line);
        if (pkg == null) pkg = "unknown";

        long now = System.currentTimeMillis();
        if (shouldLog(pkg, kind, now)) {
            Log.i(TAG, "📍 " + pkg + " accessed location (" + kind.name().toLowerCase() + ')');
            sendLog("📍 " + pkg + " accessed location (" + kind.name().toLowerCase() + ')');
            notifyUser("SecureSense Alert", "Location Accessed. Check Monitor for details.");
            remember(pkg, kind, now);
        }
    }

    /**
     * Determines whether the given package and kind of location event should be logged.
     * Returns false if a similar event was recently seen.
     */
    private boolean shouldLog(String pkg, Kind k, long now) {
        Long last = lastLog
                .getOrDefault(pkg, Map.of())
                .get(k);
        return last == null || now - last >= WINDOW_MS();
    }

    /**
     * Records the last seen timestamp for a specific package and kind.
     * Also prunes old data to keep the cache size reasonable.
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
     * Classifies a log line into one of the predefined location kinds.
     * Returns Kind.UNKNOWN if no match is found.
     */
    private Kind classify(String line) {
        for (Map.Entry<Kind, Pattern> e : KIND_PATTERNS.entrySet()) {
            if (e.getValue().matcher(line).find()) return e.getKey();
        }
        return Kind.UNKNOWN;
    }

    /**
     * Attempts to guess the name of the package responsible for the log line.
     * This uses several fallback strategies:
     * A) Token after the log TAG
     * B) Explicit "uid=... package=..." format
     * C) UID-to-package lookup via PackageManager
     * D) General regex match of a qualified package name
     */
    @Nullable
    private String guessPackage(String line) {
        // A) Try extracting tokens that look like a package name
        String[] cols = line.split("\\s+");
        if (cols.length > 5) {
            for (int i = 3; i < cols.length; i++) {
                if (cols[i].length() == 1) break;
                String t = cols[i].replace("...", "").replace("…", "");
                if (ANY_PKG.matcher(t).matches()) return t;
            }
        }

        // B) Match "uid=... package=..."
        Matcher m = UID_PKG.matcher(line);
        if (m.find()) return m.group(2);

        // C) Try resolving UID using PackageManager
        m = UID_ONLY.matcher(line);
        if (m.find() && pm != null) {
            try {
                String[] pkgs = pm.getPackagesForUid(Integer.parseInt(m.group(1)));
                if (pkgs != null && pkgs.length > 0) return pkgs[0];
            } catch (Throwable ignored) {}
        }

        // D) Last resort fallback to any token that looks like a package name
        m = ANY_PKG.matcher(line);
        return m.find() ? m.group() : null;
    }
}