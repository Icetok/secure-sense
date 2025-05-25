package me.istok.securesense.service;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.Keep;

import java.lang.reflect.Method;

/**
 * Entry point class for the Shizuku-powered user service.
 * This class acts as a wrapper around LogcatRemoteService and is instantiated by Shizuku.
 */
@Keep
public final class LogcatRemoteServiceEntryPoint extends LogcatRemoteService {

    /**
     * Default constructor used by Shizuku on API < 13 where no Context is passed.
     * It calls the parent constructor with null context, which is safe for limited use.
     */
    public LogcatRemoteServiceEntryPoint() {
        super(null); // No context is available on older versions
        Log.i("EntryPoint", "no-context constructor");
    }

    /**
     * Constructor used by Shizuku on API ≥ 13, where a package context is passed.
     * This is the preferred constructor when context is available.
     *
     * @param context The package context provided by Shizuku.
     */
    @Keep // Required by Shizuku to allow reflection-based instantiation
    public LogcatRemoteServiceEntryPoint(Context context) {
        super(context);
        Log.i("EntryPoint", "context constructor: " + context);
    }

    /**
     * Utility method to get a PackageManager instance.
     * Uses an internally obtained application context to access system services.
     *
     * @return PackageManager instance from the application context.
     */
    private static PackageManager createPM() {
        Context ctx = obtainContext();
        // Retry once if context was null (e.g., early boot or race)
        if (ctx == null)
            ctx = obtainContext();
        return ctx.getPackageManager();
    }

    /**
     * Attempts to obtain the application Context using internal Android APIs.
     * This is needed because this class is not a Service and can't call getApplicationContext().
     *
     * @return Application Context if available, otherwise throws an exception.
     */
    static Context obtainContext() {
        try {
            // First method: AppGlobals.getInitialApplication()
            Class<?> appGlobals = Class.forName("android.app.AppGlobals");
            Method getInitialApplication = appGlobals.getMethod("getInitialApplication");
            Context ctx = (Context) getInitialApplication.invoke(null);
            if (ctx != null) return ctx;

            // Second method: ActivityThread.currentApplication()
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method currentApplication = at.getMethod("currentApplication");
            return (Context) currentApplication.invoke(null);

        } catch (Throwable t) {
            // If both reflection attempts fail, crash with a meaningful message
            throw new IllegalStateException("Cannot obtain Context", t);
        }
    }

}