package me.istok.securesense;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;

import com.google.android.material.navigation.NavigationView;

import me.istok.securesense.fragment.DetectorFragment;
import me.istok.securesense.fragment.MonitorFragment;
import me.istok.securesense.fragment.SettingsFragment;
import me.istok.securesense.service.AccessMonitorService;

public class MainActivity extends AppCompatActivity {

    // UI components for navigation drawer
    private DrawerLayout drawerLayout;
    private ActionBarDrawerToggle drawerToggle;
    private NavigationView navView;

    // Request code used for permission results
    private static final int PERMISSION_REQUEST_CODE = 101;

    // Permissions required for mic, camera, and location access monitoring
    private final String[] REQUIRED_PERMISSIONS = new String[]{
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Prompt user for POST_NOTIFICATIONS permission (Android 13+)
        requestNotificationPermission();

        // Inflate the main layout
        setContentView(R.layout.activity_main);

        // Set up toolbar for the navigation drawer
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Connect the drawer layout and navigation view
        drawerLayout = findViewById(R.id.drawer_layout);
        navView = findViewById(R.id.nav_view);

        // Add a hamburger menu to toggle the drawer
        drawerToggle = new ActionBarDrawerToggle(
                this, drawerLayout, toolbar,
                R.string.navigation_drawer_open,
                R.string.navigation_drawer_close);

        drawerLayout.addDrawerListener(drawerToggle);
        drawerToggle.syncState(); // Sync toggle icon state with drawer

        // Handle navigation drawer item clicks
        navView.setNavigationItemSelectedListener(this::onNavigationItemSelected);

        // Set the initial fragment to MonitorFragment on first launch
        if (savedInstanceState == null) {
            loadFragment(new MonitorFragment());
            navView.setCheckedItem(R.id.nav_monitor);
        }

        // If all required permissions are granted, start the monitoring service
        if (hasAllPermissions()) {
            Log.d("MainActivity", "Attempting to start monitoring service");
            checkBatteryOptimization(); // Ask to disable battery optimizations for background monitoring
            startMonitorService();      // Start AccessMonitorService (foreground)
        } else {
            // If permissions not granted, request them from the user
            Log.d("MainActivity", "No perms, requesting");
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE);
        }
    }

    // Called when a drawer item is selected
    private boolean onNavigationItemSelected(@NonNull MenuItem item) {
        Fragment selectedFragment = null;
        int itemId = item.getItemId();

        // Determine which fragment to show based on selected item
        if (itemId == R.id.nav_monitor) {
            selectedFragment = new MonitorFragment();
        } else if (itemId == R.id.nav_apps) {
            selectedFragment = new DetectorFragment();
        } else if (itemId == R.id.nav_settings) {
            selectedFragment = new SettingsFragment();
        }

        // Swap the visible fragment
        if (selectedFragment != null) {
            loadFragment(selectedFragment);
        }

        // Close the drawer after selection
        drawerLayout.closeDrawers();
        return true;
    }

    // Replaces the current fragment with the given one
    private void loadFragment(Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
    }

    // Returns true only if all required permissions have been granted
    private boolean hasAllPermissions() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED)
                return false;
        }
        return true;
    }

    // Starts the background AccessMonitorService, with proper handling for Android O+
    private void startMonitorService() {
        Intent serviceIntent = new Intent(this, AccessMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent); // Use foreground service for Android O+
        } else {
            startService(serviceIntent); // Use normal service for older versions
        }
    }

    // Called after user responds to permission request
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            // If user granted all permissions, start the monitoring service
            if (hasAllPermissions()) {
                startMonitorService();
            } else {
                Toast.makeText(this, "Permissions denied. Monitoring won't start.", Toast.LENGTH_LONG).show();
            }
        }
    }

    // Requests user to exempt the app from battery optimizations
    private void checkBatteryOptimization() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent); // Prompts system dialog
        }
    }

    // Requests Android 13+ notification permission at runtime
    private void requestNotificationPermission() {
        ActivityCompat.requestPermissions(
                this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100
        );
    }
}