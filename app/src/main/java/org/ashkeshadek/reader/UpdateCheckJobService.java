package org.ashkeshadek.reader;

import android.app.job.JobParameters;
import android.app.job.JobService;

import android.util.Log;

/**
 * Framework JobScheduler check (API 21+, no external dependency, no APK
 * size cost): a few times a day the system wakes a short background probe
 * so a release published while the reader is closed still posts its shade
 * notification. Tapping it re-opens the app, which runs a fresh check and
 * offers download-and-install even for a once-dismissed release.
 *
 * Force-stopped apps (MIUI/vivo autostart restrictions) have their jobs
 * cancelled; the next app open re-arms the job, and the hourly on-open
 * check covers that case. Re-posting the same notification while a
 * release waits uninstalled is deliberate - it is the "stays visible"
 * promise; setOnlyAlertOnce keeps the repeats silent.
 */
public final class UpdateCheckJobService extends JobService {
    static final int JOB_ID = 4155;
    /** 30 minutes; JobScheduler floors periodic jobs at 15 minutes. */
    static final long PERIOD_MS = 30L * 60L * 1000L;

    @Override public boolean onStartJob(JobParameters params) {
        AppUpdater.checkForUpdates(this, new AppUpdater.CheckCallback() {
            @Override public void onUpdateAvailable(AppUpdater.UpdateInfo update) {
                MainActivity.postUpdateNotification(UpdateCheckJobService.this, update);
                MainActivity.recordUpdateCheck(UpdateCheckJobService.this);
                jobFinished(params, false);
            }

            @Override public void onNoUpdate() {
                MainActivity.recordUpdateCheck(UpdateCheckJobService.this);
                jobFinished(params, false);
            }

            @Override public void onError(String message) {
                Log.i("AshkeUpdater", "Background update check failed: " + message);
                jobFinished(params, false);
            }
        });
        // Work continues on the updater thread; jobFinished ends the job.
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        // The probe is a short read-only network check; the next periodic
        // run retries, so no immediate reschedule is requested.
        return false;
    }
}
