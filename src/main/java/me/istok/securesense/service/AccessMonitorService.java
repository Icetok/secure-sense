package me.istok.securesense.service;

import android.app.*;
import android.content.*;
import android.os.*;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import me.istok.securesense.R;
import me.istok.securesense.data.LogBuffer;
import me.istok.securesense.fragment.DetectorFragment;
import me.istok.securesense.fragment.SettingsFragment;
import me.istok.securesense.service.detector.DetectorHub;
import rikka.shizuku.Shizuku;

public class AccessMonitorService extends Service {

    // TAG used for logging
    private static final String TAG = "AccessMonitorService";

    // Foreground notification channel ID and notification ID
    private static final String CHANNEL_ID = "SecureSenseMonitoring";
    private static final int NOTIFICATION_ID = 1;

    // Time window (in milliseconds) used by detectors for log rate-limiting
    private volatile long logWindowMs = 10_000;

    // Reference to the Shizuku-bound user-service that runs the logcat monitoring
    @Nullable
    private ILogcatRemoteService remoteSvc;

    // Binder interface for receiving log lines and alerts from the user-space service
    private final ILogSink logSink = new ILogSink.Stub() {

        // Called by detectors to emit a log line to the app
        @Override
        public void onLine(String text) throws RemoteException {
            LogBuffer.add(text); // Add the line to internal buffer/history
            Intent i = new Intent("ACCESS_LOG_EVENT").putExtra("log_message", text);

            // Broadcast the log line locally to update UI (MonitorFragment)
            LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(i);
        }

        // Called by detectors to send an alert notification to the user
        @Override
        public void onAlert(String title, String msg) throws RemoteException {
            Notification n = new NotificationCompat.Builder(
                    AccessMonitorService.this, DetectorHub.Notify.ALERT_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_loc24)
                    .setContentTitle(title)
                    .setContentText(msg)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build();

            getSystemService(NotificationManager.class)
                    .notify(DetectorHub.Notify.ALERT_NOTIFICATION_ID, n);
        }
    };

    // BroadcastReceiver that listens for log interval updates from the settings UI
    private final BroadcastReceiver intervalRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (!SettingsFragment.ACTION_LOG_INTERVAL_CHANGED.equals(i.getAction())) return;

            // Update log window timing from intent extra
            int secs = i.getIntExtra(SettingsFragment.EXTRA_INTERVAL_SECS, 10);
            logWindowMs = secs * 1000L;
            DetectorHub.updateWindow(logWindowMs); // Update in DetectorHub
            Log.i(TAG, "Log interval changed to " + secs + " s");
        }
    };

    // Handles binding and communication with the Shizuku user service
    private final ServiceConnection logcatConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName n, IBinder b) {
            remoteSvc = ILogcatRemoteService.Stub.asInterface(b);
            Log.i(TAG, "user-service connected, pid=" + Binder.getCallingPid());

            try {
                // Start logcat and detection logic inside the user-service
                remoteSvc.startLogcat(logSink);
            } catch (RemoteException e) {
                Log.e(TAG, "remote call failed", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName n) {
            Log.w(TAG, "user-service disconnected");
            remoteSvc = null;
        }
    };

    // Configuration arguments for launching the Shizuku-powered logcat monitoring service
    private final Shizuku.UserServiceArgs userArgs =
            new Shizuku.UserServiceArgs(
                    new ComponentName(
                            "me.istok.securesense",
                            "me.istok.securesense.service.LogcatRemoteServiceEntryPoint"))
                    .processNameSuffix("logcat_monitor")
                    .debuggable(false)
                    .daemon(false)
                    .version(Build.VERSION.SDK_INT);

    // Called when the service is created (once)
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel(); // Foreground notification channel
        createAlertChannel(); // Alert channel for user warnings

        // Listen for detector UI toggle and interval change broadcasts
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(alertRx, new IntentFilter(DetectorHub.Notify.ACTION_SHOW_NOTIFICATION));
        registerReceivers();
    }

    // Called when the service is started via startService or startForegroundService
    @Override
    public int onStartCommand(Intent in, int flags, int id) {
        // Start in foreground with persistent notification
        startForeground(NOTIFICATION_ID, buildNotification());

        // If Shizuku is not running, stop the service
        if (!Shizuku.pingBinder()) {
            Log.e(TAG, "Shizuku not available – stopping self");
            stopSelf();
            return START_NOT_STICKY;
        }

        // Bind to the logcat monitoring user-service via Shizuku
        Shizuku.bindUserService(userArgs, logcatConn);
        return START_STICKY;
    }

    // Called when the service is destroyed
    @Override
    public void onDestroy() {
        super.onDestroy();

        // Attempt to unbind from the Shizuku user service
        try {
            Shizuku.unbindUserService(userArgs, logcatConn, true);
        } catch (Throwable t) {
            Log.w(TAG, "unbind failed", t);
        }

        // Unregister receivers
        LocalBroadcastManager.getInstance(this).unregisterReceiver(uiRx);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(intervalRx);
        unregisterReceiver(alertRx);
    }

    // This service does not support bound clients
    @Nullable
    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    // Creates the foreground notification channel
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "SecureSense monitoring",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Monitors mic, camera & location access");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    // Builds the persistent notification that appears while monitoring is active
    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SecureSense is Running")
                .setContentText("Monitoring Background Sensitive Data Access.")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setOngoing(true)
                .build();
    }

    // BroadcastReceiver for enabling/disabling individual detectors from the UI
    private final BroadcastReceiver uiRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (!DetectorFragment.ACTION_DETECTOR_STATE_CHANGED.equals(i.getAction()))
                return;

            String id = i.getStringExtra("id");
            boolean on = i.getBooleanExtra("on", true);

            // Only apply the toggle if the service is bound and connected
            if (remoteSvc == null) {
                Log.w(TAG, "user-service not yet bound – ignoring toggle");
                return;
            }

            try {
                remoteSvc.setEnabled(id, on);
            } catch (RemoteException e) {
                Log.e(TAG, "setEnabled() failed", e);
            }
        }
    };

    // BroadcastReceiver that handles alert messages as a backup path (via local broadcast)
    private final BroadcastReceiver alertRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String title = i.getStringExtra(DetectorHub.Notify.EXTRA_TITLE);
            String text = i.getStringExtra(DetectorHub.Notify.EXTRA_TEXT);

            Notification n = new NotificationCompat.Builder(c, DetectorHub.Notify.ALERT_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_loc24)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build();

            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.notify(DetectorHub.Notify.ALERT_NOTIFICATION_ID, n);
        }
    };

    // Creates the alert notification channel for high-priority access warnings
    private void createAlertChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationChannel ch = new NotificationChannel(
                DetectorHub.Notify.ALERT_CHANNEL_ID,
                "SecureSense alerts",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Mic/Camera/Location access warnings");
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    // Registers the necessary receivers for UI interaction and setting changes
    private void registerReceivers() {
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.registerReceiver(uiRx,
                new IntentFilter(DetectorFragment.ACTION_DETECTOR_STATE_CHANGED));
        lbm.registerReceiver(intervalRx,
                new IntentFilter(SettingsFragment.ACTION_LOG_INTERVAL_CHANGED));
    }
}