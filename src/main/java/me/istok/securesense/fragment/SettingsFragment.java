package me.istok.securesense.fragment;

import android.app.AppOpsManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.*;
import android.view.animation.AlphaAnimation;
import android.widget.*;

import androidx.annotation.*;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import me.istok.securesense.R;
import rikka.shizuku.Shizuku;

/**
 * Fragment that handles configuration options and permission management.
 *
 * Allows the user to:
 *  - Set the log interval for monitoring
 *  - Grant Shizuku permission
 *  - Grant Usage Stats permission
 *  - Execute a demo command via Shizuku
 *
 * Also shows permission status with icons and updates them dynamically.
 */
public class SettingsFragment extends Fragment {

    // SharedPreferences name and keys
    public static final  String PREF_NAME             = "SecureSenseSettings";
    private static final String KEY_LOG_INTERVAL_SECS = "log_interval_secs";

    // Broadcast action and extra key for interval updates
    public static final String ACTION_LOG_INTERVAL_CHANGED = "LOG_INTERVAL_CHANGED";
    public static final String EXTRA_INTERVAL_SECS        = "interval";

    private static final String TAG = "SecureSenseSettings";  // Log tag

    // UI elements
    private EditText  intervalInput;
    private TextView  statusText;
    private ImageView shizukuStatusIcon;
    private ImageView usageStatusIcon;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf,
                             @Nullable ViewGroup parent,
                             @Nullable Bundle state) {

        View v = inf.inflate(R.layout.fragment_settings, parent, false);

        // Bind views from layout
        intervalInput       = v.findViewById(R.id.scanIntervalInput);
        statusText          = v.findViewById(R.id.status_text);
        shizukuStatusIcon   = v.findViewById(R.id.shizuku_status_icon);
        usageStatusIcon     = v.findViewById(R.id.usage_status_icon);

        Button saveBtn            = v.findViewById(R.id.saveSettingsButton);
        Button reqShizukuBtn      = v.findViewById(R.id.request_permission_button);
        Button grantUsageBtn      = v.findViewById(R.id.grant_usage_access_button);
        Button runCmdBtn          = v.findViewById(R.id.run_command_button);

        // Load saved interval and display in input field
        SharedPreferences sp = requireContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        int currentSecs = sp.getInt(KEY_LOG_INTERVAL_SECS, 10);
        intervalInput.setText(String.valueOf(currentSecs));

        // Save button click handler: validates input, saves it, broadcasts change
        saveBtn.setOnClickListener(view -> {
            String txt = intervalInput.getText().toString().trim();
            if (TextUtils.isEmpty(txt)) { intervalInput.setError("Required"); return; }

            int secs;
            try { secs = Integer.parseInt(txt); }
            catch (NumberFormatException e) {
                intervalInput.setError("Invalid number"); return;
            }
            if (secs < 1) { intervalInput.setError("Must be ≥ 1"); return; }

            // Save value to SharedPreferences
            sp.edit().putInt(KEY_LOG_INTERVAL_SECS, secs).apply();

            // Broadcast new interval to the monitoring service
            Intent i = new Intent(ACTION_LOG_INTERVAL_CHANGED)
                    .putExtra(EXTRA_INTERVAL_SECS, secs);
            LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(i);

            Toast.makeText(getContext(),
                    "Log interval set to " + secs + " s", Toast.LENGTH_SHORT).show();
        });

        // Shizuku permission request button
        reqShizukuBtn.setOnClickListener(v1 -> {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(0);
            } else {
                Toast.makeText(getContext(),
                        "Shizuku permission already granted!", Toast.LENGTH_SHORT).show();
            }
        });

        // Usage access permission request button
        grantUsageBtn.setOnClickListener(v2 -> {
            if (!hasUsageStatsPermission()) {
                requestUsageStatsPermission();
            } else {
                Toast.makeText(getContext(),
                        "Usage Access already granted!", Toast.LENGTH_SHORT).show();
            }
        });

        // Run command button (requires both permissions)
        runCmdBtn.setOnClickListener(v3 -> {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                    && hasUsageStatsPermission()) {
                executePrivilegedCommand();
            } else {
                Toast.makeText(getContext(),
                        "Both Shizuku and Usage Access are required.",
                        Toast.LENGTH_SHORT).show();
            }
        });

        // Shizuku permission result callback (for dynamic status updates)
        Shizuku.addRequestPermissionResultListener((req, res) -> {
            updatePermissionIcons();
            statusText.setText(res == PackageManager.PERMISSION_GRANTED
                    ? "Shizuku permission granted."
                    : "Shizuku permission denied.");
        });

        // Initial icon update
        updatePermissionIcons();
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        // Re-check permissions when returning to fragment
        updatePermissionIcons();
    }

    /**
     * Updates status icons based on current permission state.
     * Green = granted, Red = denied
     */
    private void updatePermissionIcons() {
        Context ctx = requireContext();
        boolean shGranted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        boolean uGranted  = hasUsageStatsPermission();

        int green = ContextCompat.getColor(ctx, R.color.green);
        int red   = ContextCompat.getColor(ctx, R.color.red);

        animateStatusIcon(shizukuStatusIcon,
                shGranted ? R.drawable.confirm24 : R.drawable.cancel24,
                shGranted ? green : red);

        animateStatusIcon(usageStatusIcon,
                uGranted ? R.drawable.confirm24 : R.drawable.cancel24,
                uGranted ? green : red);
    }

    /**
     * Applies a fade-in animation when setting a new status icon and tint.
     */
    private void animateStatusIcon(ImageView icon, int drawableRes, int tintColor) {
        icon.setAlpha(0f);
        icon.setImageResource(drawableRes);
        icon.setColorFilter(tintColor);
        AlphaAnimation a = new AlphaAnimation(0f, 1f);
        a.setDuration(250);
        icon.startAnimation(a);
        icon.setAlpha(1f);
    }

    /**
     * Checks whether Usage Stats permission is granted.
     */
    private boolean hasUsageStatsPermission() {
        AppOpsManager ops = (AppOpsManager)
                requireContext().getSystemService(Context.APP_OPS_SERVICE);
        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), requireContext().getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * Launches system settings to allow user to grant Usage Stats permission.
     */
    private void requestUsageStatsPermission() {
        Intent i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                .setData(Uri.parse("package:" + requireContext().getPackageName()));
        startActivity(i);
        Toast.makeText(getContext(),
                "Enable Usage Access for this app!", Toast.LENGTH_LONG).show();
    }

    /**
     * Executes a shell command ("dumpsys power") using Shizuku and prints output.
     * Output is truncated to 500 characters and shown in a status field.
     */
    private void executePrivilegedCommand() {
        new Thread(() -> {
            try {
                Process p = new ProcessBuilder("sh", "-c", "dumpsys power")
                        .redirectErrorStream(true).start();
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream()));
                StringBuilder out = new StringBuilder();
                String ln; while ((ln = r.readLine()) != null) out.append(ln).append('\n');
                r.close(); p.waitFor();
                String res = out.toString().trim();
                requireActivity().runOnUiThread(() ->
                        statusText.setText(res.isEmpty() ? "No output" :
                                (res.length() > 500 ? res.substring(0, 500) + "…" : res)));
            } catch (IOException | InterruptedException e) {
                Log.e(TAG, "Command failed", e);
                requireActivity().runOnUiThread(() ->
                        statusText.setText("Error executing command"));
            }
        }).start();
    }
}