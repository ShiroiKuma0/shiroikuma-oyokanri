// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.settings;

import android.content.Context;

import androidx.annotation.AnyThread;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.ipc.LocalServices;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.misc.NoOps;
import io.github.muntashirakon.AppManager.utils.ContextUtils;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import rikka.shizuku.Shizuku;

/**
 * Fork: notices when the privileged session dies under us, gets it back where that is possible, and
 * says so where it is not.
 * <p>
 * <b>The hole this fills.</b> Every privileged call in the app runs through the {@code IAMService}
 * binder, and a Shizuku server restart (an update of 白い熊 雫, a manually stopped server, a killed
 * user-service process) takes that binder with it. The <i>entire</i> reaction to that used to be
 * {@code ServiceConnectionWrapper.onServiceDisconnected} setting its field to {@code null}: from
 * then on {@link LocalServices#alive()} answered false, {@code Runner.getInstance()} quietly handed
 * back the <b>no-root</b> shell and {@code ProxyBinder} fell back to local binders. Nothing threw,
 * nothing was shown, and the app went on looking exactly like a working one while every privileged
 * operation silently did nothing. The only cure was restarting the app, because {@link
 * Ops#init(Context, boolean)} is reached from the splash gate and the settings mode picker and from
 * nowhere else.
 * <p>
 * <b>Reclaiming is free when it is possible at all.</b> Authorisation is held by the <i>server's</i>
 * own persisted list, so it survives the restart — a rebind needs no prompt and no interaction. The
 * server even tells us it is back: it pushes a fresh binder into our {@code ShizukuProvider}, which
 * lands in {@link Shizuku#onBinderReceived}. Both that and the death notice already existed and were
 * simply never listened for — the app registered no {@link Shizuku.OnBinderDeadListener} anywhere,
 * and its one {@link Shizuku.OnBinderReceivedListener} was the transient sticky one inside
 * {@link ShizukuOps#awaitBinder(long)}, removed in its own {@code finally}. This class is the
 * listener that stays.
 * <p>
 * <b>The reclaim is {@link Ops#init(Context, boolean)}, not a bind of our own.</b> That method is
 * the one place that knows how each mode is entered, corrects the session flags from the uid the
 * service actually came up as, and re-runs {@link io.github.muntashirakon.AppManager.self.SelfPermissions#init()}.
 * Duplicating a bind here would be a second copy of all of that, free to drift.
 * <p>
 * <b>Landmine — a deliberate teardown looks exactly like a death.</b> Switching modes tears the old
 * service down before binding the new one, and that fires the same {@code onServiceDisconnected}.
 * Every such teardown in the app happens inside {@code Ops.init} ({@code LocalServices.stopServices}
 * has no other caller, and {@code unbindServices} is only reached from {@code bindServices}), so
 * {@code Ops.init} brackets itself with {@link #noteInitStarted()}/{@link #noteInitFinished()} and a
 * drop seen inside those brackets is ours, not a loss. No timer can tell the two apart.
 * <p>
 * <b>Nothing is alarming until something is actually lost.</b> {@link #sHadPrivileges} latches the
 * first time the services are seen alive, and the banner is driven off it — so a phone that never
 * had privileges (no server, no ADB, an install that has always been no-root) never sees a red bar
 * accusing it of losing something it never had.
 */
public final class PrivilegeWatchdog {
    public static final String TAG = PrivilegeWatchdog.class.getSimpleName();

    @IntDef({STATE_OK, STATE_LOST, STATE_RECLAIMING})
    @Retention(RetentionPolicy.SOURCE)
    public @interface State {
    }

    /** The privileged services are up, or were never expected. */
    public static final int STATE_OK = 0;
    /** Privileges were held in this session and are gone. */
    public static final int STATE_LOST = 1;
    /** A reclaim is in flight. */
    public static final int STATE_RECLAIMING = 2;

    /**
     * How long a dropped service is given before it counts as lost. Covers the gap between a
     * teardown and the rebind that follows it on paths where the two are not both inside
     * {@code Ops.init} — the bracket is the real guard, this is only slack.
     */
    private static final long VERIFY_DELAY_MS = 2_000L;

    private static final MutableLiveData<Integer> sState = new MutableLiveData<>(STATE_OK);
    private static final AtomicBoolean sInstalled = new AtomicBoolean(false);
    private static final AtomicBoolean sReclaiming = new AtomicBoolean(false);
    private static final AtomicInteger sInitDepth = new AtomicInteger(0);
    /** Whether this session has ever actually held privileges. See the class note. */
    private static volatile boolean sHadPrivileges = false;

    private PrivilegeWatchdog() {
    }

    /**
     * Register the two Shizuku listeners, once per process. Called from
     * {@link io.github.muntashirakon.AppManager.AppManager#onCreate()}: the server pushes its binder
     * whenever it likes, including while no activity of ours exists, so the listeners have to
     * outlive every screen.
     */
    @AnyThread
    @NoOps
    public static void install(@NonNull Context context) {
        if (!sInstalled.compareAndSet(false, true)) {
            return;
        }
        Context appContext = context.getApplicationContext();
        try {
            Shizuku.addBinderReceivedListener(() -> onBinderReceived(appContext));
            Shizuku.addBinderDeadListener(PrivilegeWatchdog::onBinderDead);
        } catch (Throwable th) {
            // A missing Shizuku runtime must never cost us the app; the on-resume refresh below
            // still notices a loss, it just cannot fix it by itself.
            Log.w(TAG, "Could not register the Shizuku binder listeners", th);
        }
    }

    @AnyThread
    @NonNull
    public static LiveData<Integer> getState() {
        return sState;
    }

    /**
     * Bracket for {@link Ops#init(Context, boolean)} — see the landmine in the class note. Nested
     * because auto-detection can re-enter, and counted rather than a flag for the same reason.
     */
    @AnyThread
    @NoOps
    static void noteInitStarted() {
        sInitDepth.incrementAndGet();
    }

    @AnyThread
    @NoOps
    static void noteInitFinished() {
        sInitDepth.decrementAndGet();
        // Whatever init settled on is the truth, including "we have privileges now" — this is what
        // latches sHadPrivileges on a normal cold start.
        refresh();
    }

    /**
     * The AM service binder dropped. Called from {@code ServiceConnectionWrapper} for every way it
     * can go: a disconnect, a died binding, a null binding.
     */
    @AnyThread
    @NoOps
    public static void onServiceLost() {
        if (!sHadPrivileges || sInitDepth.get() > 0) {
            return;
        }
        ThreadUtils.postOnMainThreadDelayed(PrivilegeWatchdog::refresh, VERIFY_DELAY_MS);
    }

    /**
     * Recompute the state from what is actually true right now, and start a silent reclaim if one
     * can be had. Safe from any thread — the checks below are binder calls, so a main-thread caller
     * is bounced to a worker rather than made to wait.
     */
    @AnyThread
    @NoOps
    public static void refresh() {
        if (ThreadUtils.isMainThread()) {
            ThreadUtils.postOnBackgroundThread(PrivilegeWatchdog::refreshInternal);
        } else {
            refreshInternal();
        }
    }

    @WorkerThread
    @NoOps
    private static void refreshInternal() {
        if (LocalServices.alive()) {
            sHadPrivileges = true;
            setState(STATE_OK);
            return;
        }
        if (sInitDepth.get() > 0 || sReclaiming.get()) {
            // A bind is in flight; whatever it settles on is the answer, not this snapshot.
            return;
        }
        if (!sHadPrivileges || !Ops.isAuthenticated()) {
            setState(STATE_OK);
            return;
        }
        setState(STATE_LOST);
        if (shizukuIsTheChosenRoute() && ShizukuOps.isReady()) {
            // A live server that still trusts us: no prompt is needed, so do not wait to be asked.
            reclaim(ContextUtils.getContext(), false);
        }
    }

    /**
     * A server came back and pushed us a binder. This is the whole automatic half: the push is the
     * event, and an authorised server means the rebind costs nothing but a binder transaction.
     * <p>
     * Deliberately <i>not</i> gated on {@link #sHadPrivileges} — starting the server after opening
     * the app is the ordinary way round, and that case has never held privileges yet.
     */
    private static void onBinderReceived(@NonNull Context context) {
        // Dispatched on the main looper by the Shizuku singleton; everything below is binder work.
        ThreadUtils.postOnBackgroundThread(() -> {
            if (LocalServices.alive() || sInitDepth.get() > 0 || !Ops.isAuthenticated()) {
                return;
            }
            if (!shizukuIsTheChosenRoute() || !ShizukuOps.isReady()) {
                return;
            }
            Log.i(TAG, "A Shizuku server is back and still authorises us; rebinding.");
            reclaim(context, false);
        });
    }

    private static void onBinderDead() {
        Log.i(TAG, "The Shizuku server binder died.");
        onServiceLost();
    }

    /**
     * Whether Shizuku is the route this session should be taking. {@link Ops#MODE_AUTO} counts:
     * auto-detection hunts Shizuku ahead of ADB, and the fork deliberately leaves the preference on
     * auto whenever a manager is installed (see {@code Ops.setModeFromAutoDetection}), so an auto
     * install is exactly the one whose next look should find the server that just appeared.
     */
    @AnyThread
    @NoOps
    private static boolean shizukuIsTheChosenRoute() {
        if (Ops.isShizuku()) {
            return true;
        }
        String mode = Ops.getMode();
        return Ops.MODE_SHIZUKU.equals(mode) || Ops.MODE_AUTO.equals(mode);
    }

    /**
     * Ask for the privileges back.
     *
     * @param interactive whether 白い熊 may be prompted — {@code true} only for the tap on the alarm
     *                    itself. The automatic paths pass {@code false} and gate themselves on
     *                    {@link ShizukuOps#isReady()} beforehand, so {@code Ops.init} finds the
     *                    authorisation already in place and raises nothing.
     */
    @AnyThread
    @NoOps
    public static void reclaimAsync(@NonNull Context context, boolean interactive) {
        Context appContext = context.getApplicationContext();
        ThreadUtils.postOnBackgroundThread(() -> reclaim(appContext, interactive));
    }

    @WorkerThread
    @NoOps
    private static boolean reclaim(@NonNull Context context, boolean interactive) {
        if (!sReclaiming.compareAndSet(false, true)) {
            Log.d(TAG, "A reclaim is already running.");
            return false;
        }
        try {
            if (LocalServices.alive()) {
                sHadPrivileges = true;
                setState(STATE_OK);
                return true;
            }
            setState(STATE_RECLAIMING);
            // force=false: a service that is somehow alive again is kept rather than torn down and
            // rebuilt. Ops.init announces the outcome itself (the "Working on … mode" toast), which
            // is the right report for a reclaim too.
            Ops.init(context, false);
        } catch (Throwable th) {
            Log.e(TAG, "Could not reclaim privileges.", th);
        } finally {
            sReclaiming.set(false);
        }
        boolean ok = LocalServices.alive();
        if (ok) {
            sHadPrivileges = true;
            setState(STATE_OK);
            Log.i(TAG, "Privileges reclaimed.");
        } else {
            setState(sHadPrivileges ? STATE_LOST : STATE_OK);
            Log.w(TAG, "Could not reclaim privileges (interactive=%b).", interactive);
        }
        return ok;
    }

    /**
     * What the session <i>should</i> be running as, for the alarm to name. Read from the session
     * flags first and the stored preference second: uid 2000 is reached by two different routes and
     * only the flag knows which one this session took.
     */
    @AnyThread
    @NoOps
    @NonNull
    public static CharSequence expectedModeLabel(@NonNull Context context) {
        if (Ops.isShizuku()) {
            return context.getString(R.string.shizuku);
        }
        String mode = Ops.getMode();
        if (Ops.MODE_SHIZUKU.equals(mode)) {
            return context.getString(R.string.shizuku);
        }
        if (Ops.MODE_ROOT.equals(mode) || Ops.isDirectRoot()) {
            return context.getString(R.string.root);
        }
        return "ADB";
    }

    @AnyThread
    private static void setState(@State int state) {
        Integer current = sState.getValue();
        if (current != null && current == state) {
            return;
        }
        sState.postValue(state);
    }
}
