package nl.axlproductions.kiaklimaatplanner;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BatterySampling extends JobService {
    static final String ENABLED = "sampleBatteryHourly";
    static final int JOB_ID = 412;
    static final long INTERVAL = 60 * Schedule.MINUTE;
    private AtomicBoolean activeRun;

    static boolean enabled(Context context) {
        return Planner.prefs(context).getBoolean(ENABLED, true);
    }

    static void updateSchedule(Context context) {
        var scheduler = context.getSystemService(JobScheduler.class);
        if (!enabled(context) || !KiaCloud.linked(context) || KiaCloud.needsLink(context)) {
            scheduler.cancel(JOB_ID);
            return;
        }
        if (scheduler.getPendingJob(JOB_ID) != null) return;
        scheduler.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, BatterySampling.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(INTERVAL).build());
    }

    @Override public boolean onStartJob(JobParameters params) {
        if (!enabled(this) || !KiaCloud.linked(this) || KiaCloud.needsLink(this) || KiaBackoff.paused(this)) return false;
        var stopped = new AtomicBoolean();
        activeRun = stopped;
        KiaCloud.WORKER.execute(() -> {
            try {
                if (!stopped.get() && enabled(this)) KiaCloud.sampleBattery(getApplicationContext());
            } finally {
                if (!stopped.get()) jobFinished(params, false);
            }
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (activeRun != null) activeRun.set(true);
        return false;
    }
}
