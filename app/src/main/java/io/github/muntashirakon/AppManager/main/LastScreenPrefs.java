// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import io.github.muntashirakon.AppManager.battery.BatteryAppDetailActivity;
import io.github.muntashirakon.AppManager.battery.BatteryUsageActivity;
import io.github.muntashirakon.AppManager.details.AppDetailsActivity;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.processreaper.ProcessDetailActivity;
import io.github.muntashirakon.AppManager.processreaper.ProcessMonitorActivity;
import io.github.muntashirakon.AppManager.screens.ListScreenActivity;

/**
 * Fork (白い熊, +146): reopen the screen you were on.
 *
 * <p>Leaving 保存一覧 or an app's 盗み見 page for the launcher and coming back landed you on the
 * main list. The +97 splash trampoline already handles the easy half — while the task still
 * exists, {@code SplashActivity} finishes and the screen underneath simply resumes — but it can
 * only ever resume a task that is still there. EMUI kills our process and drops the task; Android
 * resets a task down to its root when it is returned to after long enough away. Then the launcher
 * builds a fresh task, {@code MainActivity} is created for real, and no amount of task-stack
 * cleverness can recall a screen that no longer exists anywhere. It has to be <b>written down</b>.
 *
 * <p><b>One hook, not a line per screen.</b> An {@code ActivityLifecycleCallbacks} registered for
 * the life of the process records the resumed activity's own {@link Intent}, serialised with
 * {@link Intent#toUri}. That covers every screen at once — and every screen added later, provided
 * it is named in {@link #REOPENABLE}.
 *
 * <p><b>An allowlist, deliberately.</b> The tempting shape is "record everything except a
 * blocklist", and it is wrong here: this app is full of activities that must never be replayed —
 * the installer, the keystore prompt, the freeze-unfreeze trampoline, the progress page of an
 * operation that has since finished — and an intent carrying a {@code content://} URI would be
 * replayed without the permission grant that made it work. So a screen is reopened only if it is
 * listed, and only if its intent carries no data URI.
 *
 * <p>Returning to the main list clears the memory by itself: {@code MainActivity} is recorded like
 * anything else, and recording it means "nothing to reopen".
 *
 * <p>The file is excluded from Export/Import — it is where <em>this</em> phone was, and an app
 * details intent for a package another phone does not have is worse than no memory at all.
 */
public final class LastScreenPrefs {
    public static final String TAG = LastScreenPrefs.class.getSimpleName();
    public static final String PREF_FILE = "shiroikuma_last_screen";
    private static final String KEY_INTENT = "intent";
    private static final String KEY_ENABLED = "reopen";

    /** Screens worth coming back to. Class objects, so a rename cannot silently disable this. */
    private static final Set<String> REOPENABLE = new HashSet<>(Arrays.asList(
            ListScreenActivity.class.getName(),
            AppDetailsActivity.class.getName(),
            BatteryUsageActivity.class.getName(),
            BatteryAppDetailActivity.class.getName(),
            ProcessMonitorActivity.class.getName(),
            ProcessDetailActivity.class.getName()));

    private LastScreenPrefs() {
    }

    public static void install(@NonNull Application application) {
        application.registerActivityLifecycleCallbacks(new Callbacks());
    }

    public static boolean isEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /**
     * The screen to reopen, or {@code null}.
     *
     * <p><b>LANDMINE — this must NOT be latched to once per process (白い熊, +147).</b> It was, and
     * that is what made the feature look dead: the first {@code MainActivity} of a process
     * consumes the answer, and on a phone where a launcher tap creates a <em>second</em>
     * {@code MainActivity} while the process is still alive — which EMUI does, and the task stack
     * shows it plainly as repeating {@code [Main, screen, Main, screen]} pairs — every launch after
     * the first found the latch already thrown and quietly went to the app list.
     *
     * <p>What replaces it is the record itself: reading it <b>clears</b> it, so a second
     * {@code MainActivity} finds nothing. Nothing is lost, because the screen we are about to
     * start records itself again the moment it resumes — the memory is rebuilt within the frame.
     */
    @Nullable
    public static Intent consume(@NonNull Context context) {
        if (!isEnabled(context)) {
            return null;
        }
        String uri = prefs(context).getString(KEY_INTENT, null);
        if (uri == null) {
            Log.d(TAG, "Nothing to reopen.");
            return null;
        }
        prefs(context).edit().remove(KEY_INTENT).apply();
        try {
            Intent intent = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
            if (intent.getComponent() == null
                    || !REOPENABLE.contains(intent.getComponent().getClassName())) {
                Log.d(TAG, "Not reopenable: %s", uri);
                return null;
            }
            // The package is pinned again on the way out: parseUri restores whatever was written,
            // and this intent is about to be started with our own authority.
            intent.setPackage(context.getPackageName());
            Log.d(TAG, "Reopening %s", intent.getComponent().getClassName());
            return intent;
        } catch (Throwable th) {
            Log.d(TAG, "Could not parse the remembered screen: %s", uri);
            return null;
        }
    }

    private static void record(@NonNull Activity activity) {
        String name = activity.getClass().getName();
        boolean main = MainActivity.class.getName().equals(name);
        if (!main && !REOPENABLE.contains(name)) {
            // Not a screen this feature is about — and not the main list either, so it says
            // nothing about where the user has settled. Leave the memory as it stands.
            return;
        }
        SharedPreferences prefs = prefs(activity);
        if (main) {
            prefs.edit().remove(KEY_INTENT).apply();
            return;
        }
        Intent intent = activity.getIntent();
        if (intent == null || intent.getData() != null) {
            // A data URI is normally a content:// grant that will not survive the replay.
            return;
        }
        try {
            Intent copy = new Intent(intent);
            copy.setComponent(new android.content.ComponentName(activity, activity.getClass()));
            copy.setFlags(0);
            prefs.edit().putString(KEY_INTENT, copy.toUri(Intent.URI_INTENT_SCHEME)).apply();
            Log.d(TAG, "Remembered %s", name);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    private static class Callbacks implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityResumed(@NonNull Activity activity) {
            record(activity);
        }

        @Override
        public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        }

        @Override
        public void onActivityStarted(@NonNull Activity activity) {
        }

        @Override
        public void onActivityPaused(@NonNull Activity activity) {
        }

        @Override
        public void onActivityStopped(@NonNull Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(@NonNull Activity activity) {
        }
    }
}
