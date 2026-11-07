package com.xsr.companion.sync;

import android.app.job.JobParameters;
import android.app.job.JobService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * JobService invoked by JobScheduler for routine daily push-sync.
 */
public class SyncJobService extends JobService {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean isCancelled = false;

    @Override
    public boolean onStartJob(JobParameters params) {
        isCancelled = false;
        AuthManager auth = AuthManager.getInstance(this);
        if (!auth.isSignedIn()) {
            return false;
        }

        executor.execute(() -> {
            if (isCancelled) return;
            SyncManager.SyncResult result = SyncManager.getInstance(this).executeRoutinePushSync();
            boolean needsReschedule = !result.success && !isCancelled;
            jobFinished(params, needsReschedule);
        });

        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        isCancelled = true;
        return true; // Reschedule since the job was interrupted
    }
}
