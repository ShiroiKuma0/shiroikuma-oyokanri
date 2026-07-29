// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.battery;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import androidx.annotation.NonNull;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import io.github.muntashirakon.AppManager.logs.Log;

/**
 * Fork: the unattended half of the battery history — one reading every
 * {@link BatteryPrefs#getIntervalMinutes} minutes.
 *
 * <p><b>Why a periodic job and not a service or an alarm.</b> The counters
 * being sampled are cumulative, so a late reading loses nothing: it just widens
 * one bucket. That makes this the rare monitor that can be fully Doze-friendly
 * — no wakelock, no foreground service, no exact alarm, and the OS free to
 * batch us with whatever else it was already waking for. A monitor that wakes
 * the phone to measure why the phone is awake would be its own worst finding.
 *
 * <p>The job is {@code setPersisted}, so it survives reboot on its own;
 * {@code BootReceiver} re-schedules as a belt-and-braces second path.
 */
public class BatterySamplerJob extends JobService {
    public static final String TAG = BatterySamplerJob.class.getSimpleName();
    private static final int JOB_ID = 0x5AFE_BA77;

    private static final Executor EXECUTOR = Executors.newSingleThreadExecutor();

    /** (Re)schedules the periodic sampler, or cancels it when disabled. */
    public static void schedule(@NonNull Context context) {
        Context app = context.getApplicationContext();
        JobScheduler scheduler = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        if (!BatteryPrefs.isEnabled(app)) {
            scheduler.cancel(JOB_ID);
            return;
        }
        long intervalMs = BatteryPrefs.getIntervalMinutes(app) * 60_000L;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(app, BatterySamplerJob.class))
                .setPeriodic(intervalMs)
                .setPersisted(true)
                .setRequiresDeviceIdle(false)
                .setRequiresCharging(false)
                .build();
        try {
            scheduler.schedule(job);
        } catch (Throwable th) {
            Log.w(TAG, "Could not schedule the battery sampler", th);
        }
    }

    public static void cancel(@NonNull Context context) {
        JobScheduler scheduler = (JobScheduler) context.getApplicationContext()
                .getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler != null) scheduler.cancel(JOB_ID);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        EXECUTOR.execute(() -> {
            try {
                BatterySampler.sample(getApplicationContext());
            } catch (Throwable th) {
                Log.e(TAG, "Battery sampling failed", th);
            } finally {
                jobFinished(params, false);
            }
        });
        return true; // work continues on the executor
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // Nothing to unwind: a dropped reading only widens the next bucket.
        return true;
    }
}
