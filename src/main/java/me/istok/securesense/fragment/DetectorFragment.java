package me.istok.securesense.fragment;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Switch;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.istok.securesense.R;

/**
 * Fragment that allows the user to toggle individual detectors on or off.
 * Detectors are components that monitor sensitive resource usage like
 * location, microphone, and camera.
 *
 * State is persisted using SharedPreferences under the name "DetectorPrefs".
 * When toggled, a local broadcast is sent to notify background services.
 */
public class DetectorFragment extends Fragment {

    // Broadcast action to notify other components that a detector's state changed
    public static final String ACTION_DETECTOR_STATE_CHANGED = "DETECTOR_STATE_CHANGED";

    // (Optional) Extra map for batch state updates – not currently used
    public static final String EXTRA_STATE_MAP = "state_map";

    /**
     * Internal model representing a single toggle row for a detector.
     */
    private static class DetectorInfo {
        final String id;        // Stable identifier (used in prefs and broadcast)
        final String title;     // Display name in UI
        final String subtitle;  // Short description below the title
        final int iconRes;      // Drawable resource for the icon

        DetectorInfo(String id, String title, String subtitle, int iconRes) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.iconRes = iconRes;
        }
    }

    /**
     * List of detectors the app currently supports.
     * Add new detectors here as needed.
     */
    private static final List<DetectorInfo> DETECTORS = new ArrayList<>();
    static {
        DETECTORS.add(new DetectorInfo("location",  "Location",
                "GPS / Cell / Wi-Fi scans",           R.drawable.ic_loc24));
        DETECTORS.add(new DetectorInfo("microphone","Microphone",
                "Recording / hot-word / STT",         R.drawable.ic_mic24));
        DETECTORS.add(new DetectorInfo("camera",    "Camera",
                "Photo / video / preview",            R.drawable.ic_cam24));
    }

    // Name of the SharedPreferences file used to store toggle state
    private static final String PREFS_NAME = "DetectorPrefs";

    private SharedPreferences prefs;   // Backing storage for toggle states
    private DetectorAdapter adapter;   // RecyclerView adapter for detector list

    public DetectorFragment() {}

    /**
     * Called when the view is created. Sets up the RecyclerView and loads the toggle states.
     */
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View v = inflater.inflate(R.layout.fragment_detectors, container, false);

        prefs   = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        adapter = new DetectorAdapter(DETECTORS, this::onToggleChanged, prefs);

        RecyclerView rv = v.findViewById(R.id.detectorRecycler);
        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        rv.setAdapter(adapter);

        return v;
    }

    /**
     * Called when the user toggles a detector switch.
     * Saves the new state and sends a local broadcast to notify listeners.
     */
    private void onToggleChanged(String detectorId, boolean enabled) {
        // Save the updated toggle state
        prefs.edit().putBoolean(detectorId, enabled).apply();

        // Send broadcast with single detector's state
        Intent i = new Intent(ACTION_DETECTOR_STATE_CHANGED)
                .putExtra("id",  detectorId)
                .putExtra("on",  enabled);
        LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(i);
    }

    /**
     * RecyclerView adapter to display the list of detectors and their toggle switches.
     */
    private static class DetectorAdapter
            extends RecyclerView.Adapter<DetectorAdapter.VH> {

        interface ToggleListener {
            void onToggle(String id, boolean on);
        }

        private final List<DetectorInfo> data;     // list of detectors to show
        private final ToggleListener listener;     // callback for toggle changes
        private final SharedPreferences prefs;     // persistent toggle state

        DetectorAdapter(List<DetectorInfo> d, ToggleListener l, SharedPreferences p) {
            data     = d;
            listener = l;
            prefs    = p;
        }

        /**
         * ViewHolder class representing a single detector toggle row.
         */
        static class VH extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView title;
            final TextView sub;
            final Switch sw;

            VH(View v) {
                super(v);
                icon  = v.findViewById(R.id.detector_icon);
                title = v.findViewById(R.id.detector_title);
                sub   = v.findViewById(R.id.detector_subtitle);
                sw    = v.findViewById(R.id.detector_switch);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            View row = LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_detector, p, false);
            return new VH(row);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            DetectorInfo d = data.get(pos);

            h.icon.setImageResource(d.iconRes);
            h.title.setText(d.title);
            h.sub.setText(d.subtitle);

            // Load and apply saved toggle state
            boolean enabled = prefs.getBoolean(d.id, true);

            // Prevent triggering the listener during setChecked()
            h.sw.setOnCheckedChangeListener(null);
            h.sw.setChecked(enabled);

            // Reattach listener after updating toggle state
            h.sw.setOnCheckedChangeListener((CompoundButton b, boolean on) ->
                    listener.onToggle(d.id, on));
        }

        @Override
        public int getItemCount() {
            return data.size();
        }
    }
}