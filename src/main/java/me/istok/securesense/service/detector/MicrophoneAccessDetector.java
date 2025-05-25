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
 * Detects attempts to access the microphone from logcat output.
 * This includes usage of MediaRecorder, AudioRecord, codecs, voice assistants,
 * speech-to-text systems, and audio focus changes.
 *
 * Applies per-app and per-type rate-limiting to avoid notification spam.
 */
public class MicrophoneAccessDetector extends Detector implements LogcatDetector {

    // Tag used for logging within this class
    private static final String TAG = "MicAccess";

    /**
     * Types of microphone-related log events we are interested in.
     * These allow more granular control of rate limiting per type.
     */
    private enum Kind {
        MEDIARECORDER, CODEC, AUDIORECORD,
        WRITER, HOTWORD, SERVICE, STT, AUDIO_FOCUS,
        UNKNOWN
    }

    /**
     * Map of regex patterns used to classify logcat lines by type of microphone access.
     * This is the core of the detection mechanism.
     */
    private static final Map<Kind, Pattern> KIND_PATTERNS = new LinkedHashMap<>();
    static {
        KIND_PATTERNS.put(Kind.MEDIARECORDER,
                Pattern.compile("(MediaRecorder|start recording)", Pattern.CASE_INSENSITIVE));

        KIND_PATTERNS.put(Kind.CODEC,
                Pattern.compile("(MediaCodec|CCodec).*encoder|aac\\.encoder", Pattern.CASE_INSENSITIVE));

        KIND_PATTERNS.put(Kind.AUDIORECORD,
                Pattern.compile("AudioRecord.*(start|read)|mic_input", Pattern.CASE_INSENSITIVE));

        KIND_PATTERNS.put(Kind.WRITER,
                Pattern.compile("MPEG4Writer.*setStartTimestampUs", Pattern.CASE_INSENSITIVE));

        KIND_PATTERNS.put(Kind.HOTWORD,
                Pattern.compile("(SoundTrigger|Hotword).*?(start|capture)", Pattern.CASE_INSENSITIVE));

        KIND_PATTERNS.put(Kind.STT,
                Pattern.compile("(AudioInputStreamProducer|SodaSpeechRecognizer|" +
                        "NetworkSpeechRecognizer|RecognitionClient)", Pattern.CASE_INSENSITIVE));

        KIND_PATTERNS.put(Kind.AUDIO_FOCUS,
                Pattern.compile("(#audio#.*?(acquire|activat|opening|start)|AudioFocus)",
                        Pattern.CASE_INSENSITIVE));

        KIND_PATTERNS.put(Kind.SERVICE,
                Pattern.compile("AudioService.*Start recording use case", Pattern.CASE_INSENSITIVE));
    }

    // Regex helpers to extract UID and package name from log lines
    private static final Pattern UID_PKG  = Pattern.compile("uid=(\\d+).*?package=([\\w.]+)");
    private static final Pattern UID_ONLY = Pattern.compile("uid=(\\d+)");
    private static final Pattern ANY_PKG  = Pattern.compile("[A-Za-z]\\w*(?:\\.[A-Za-z]\\w*)+");

    // Keeps track of when each (package, kind) pair last triggered an alert
    private final Map<String, Map<Kind, Long>> lastLog = new LinkedHashMap<>();

    // Optional PackageManager used to resolve UID to package names
    private final @Nullable PackageManager pm;

    // Optional context provided during instantiation (may be null)
    private Context context;

    /**
     * Constructs a microphone access detector, optionally providing a Context.
     * Context is used to retrieve the PackageManager for UID resolution.
     */
    public MicrophoneAccessDetector(@Nullable Context ctx) {
        pm = (ctx != null) ? ctx.getPackageManager() : null;
        this.context = ctx;
    }

    /**
     * Unique identifier used to register this detector.
     */
    @NonNull
    @Override
    public String id() {
        return "microphone";
    }

    /**
     * Cheap pre-filter to avoid applying regex to every line.
     * Only returns true if the line contains some known microphone keywords.
     */
    @Override
    public boolean matches(String l) {
        return l.contains("#audio#")                  || l.contains("RecognitionClient")
                || l.contains("MediaRecorder")        || l.contains("MediaCodec")
                || l.contains("CCodec")               || l.contains("AudioRecord")
                || l.contains("SoundTrigger")         || l.contains("MPEG4Writer")
                || l.contains("mic_input")            || l.contains("AudioInputStreamProducer")
                || l.contains("SodaSpeechRecognizer") || l.contains("NetworkSpeechRecognizer");
    }

    /**
     * Handles a matching log line. Determines the kind of event,
     * identifies the responsible package, applies per-type rate limiting,
     * then logs the access and triggers a user notification.
     */
    @Override
    public void handle(String line) {
        Kind kind = classify(line);
        if (kind == Kind.UNKNOWN) return;

        String pkg = guessPackage(line);
        if (pkg == null) pkg = "unknown";

        long now = System.currentTimeMillis();
        if (shouldLog(pkg, kind, now)) {
            Log.i(TAG, "🎙️  " + pkg + " accessed microphone (" +
                    kind.name().toLowerCase() + ')');
            sendLog("🎙️  " + pkg + " accessed microphone (" + kind.name().toLowerCase() + ')');
            notifyUser("SecureSense Alert", "Microphone Accessed. Check Monitor for details.");
            remember(pkg, kind, now);
        }
    }

    /**
     * Classifies a log line into one of the known microphone-related types
     * using the predefined KIND_PATTERNS.
     */
    private Kind classify(String line) {
        for (var e : KIND_PATTERNS.entrySet()) {
            if (e.getValue().matcher(line).find()) return e.getKey();
        }
        return Kind.UNKNOWN;
    }

    /**
     * Checks if a (package, kind) combination is eligible for logging/notification,
     * based on the time since the last occurrence.
     */
    private boolean shouldLog(String pkg, Kind k, long now) {
        Long last = lastLog.getOrDefault(pkg, Map.of()).get(k);
        return last == null || now - last >= WINDOW_MS();
    }

    /**
     * Records the current timestamp for the (package, kind) pair,
     * and prunes old entries to avoid unbounded memory use.
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
     * Attempts to determine the name of the package responsible for the log line.
     * Uses multiple fallback strategies:
     *  A) Parse package from columns after log tag
     *  B) Match "uid=... package=..."
     *  C) Use PackageManager to resolve UID
     *  D) Any generic package-looking token
     */
    @Nullable
    private String guessPackage(String line) {
        // A) Column after log tag
        String[] cols = line.split("\\s+");
        if (cols.length > 5) {
            for (int i = 3; i < cols.length; i++) {
                if (cols[i].length() == 1) break;
                String tok = cols[i].replace("...", "").replace("…", "");
                if (ANY_PKG.matcher(tok).matches()) return tok;
            }
        }

        // B) Direct match for "uid=... package=..."
        Matcher m = UID_PKG.matcher(line);
        if (m.find()) return m.group(2);

        // C) UID lookup via PackageManager
        m = UID_ONLY.matcher(line);
        if (m.find() && pm != null) {
            try {
                String[] pkgs = pm.getPackagesForUid(Integer.parseInt(m.group(1)));
                if (pkgs != null && pkgs.length > 0) return pkgs[0];
            } catch (Throwable ignored) { }
        }

        // D) Fallback: search for any package-looking token
        m = ANY_PKG.matcher(line);
        return m.find() ? m.group() : null;
    }
}