package com.xsr.companion.sync;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

import java.util.concurrent.TimeUnit;

/**
 * Handles scheduling, stale checks, and cancellation of cloud sync background jobs.
 */
public final class SyncScheduler {

    public static final int JOB_DAILY = 101;
    public static final int JOB_ONEOFF = 102;

    private static final long DAILY_INTERVAL_MS = TimeUnit.HOURS.toMillis(24);
    private static final long FLEX_INTERVAL_MS = TimeUnit.HOURS.toMillis(1);
    private static final long INITIAL_BACKOFF_MS = TimeUnit.MINUTES.toMillis(15);

    private SyncScheduler() {}

    public static void scheduleDaily(Context context) {
        JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;

        // Check if daily job is already scheduled
        JobInfo pending = js.getPendingJob(JOB_DAILY);
        if (pending != null) return;

        JobInfo job = new JobInfo.Builder(JOB_DAILY, new ComponentName(context, SyncJobService.class))
                .setPeriodic(DAILY_INTERVAL_MS, FLEX_INTERVAL_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setBackoffCriteria(INITIAL_BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build();
        js.schedule(job);
    }

    public static void syncIfStale(Context context) {
        AuthManager auth = AuthManager.getInstance(context);
        if (!auth.isSignedIn()) return;

        long lastSync = auth.getLastSyncAt();
        long now = System.currentTimeMillis();
        if (lastSync <= 0 || (now - lastSync >= DAILY_INTERVAL_MS)) {
            JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;

            JobInfo job = new JobInfo.Builder(JOB_ONEOFF, new ComponentName(context, SyncJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setBackoffCriteria(INITIAL_BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .build();
            js.schedule(job);
        }
    }

    public static void cancel(Context context) {
        JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) {
            js.cancel(JOB_DAILY);
            js.cancel(JOB_ONEOFF);
        }
    }
}
